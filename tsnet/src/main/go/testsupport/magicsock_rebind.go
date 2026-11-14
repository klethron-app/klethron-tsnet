//go:build klethron_test

// Copied into the pinned magicsock package only for Android instrumentation.
package magicsock

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/tailscale/wireguard-go/device"
	"tailscale.com/derp"
	"tailscale.com/derp/derphttp"
	"tailscale.com/derp/derpserver"
	"tailscale.com/health"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tstime/mono"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/util/eventbus"
	"tailscale.com/util/usermetric"
)

// KlethronProbePermissionRecovery must run without other nodes or network-report callbacks.
// The initial relay is held before its HTTP upgrade, with a packet in the production write queue.
func KlethronProbePermissionRecovery(mode string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	bus := eventbus.New()
	defer bus.Close()
	// Snapshot a known default interface so the old full rebind takes its DERP-reset path.
	previous, _ := netmon.DefaultRouteInterface()
	netmon.UpdateLastKnownDefaultRouteInterface("lo")
	monitor := netmon.NewStatic()
	netmon.UpdateLastKnownDefaultRouteInterface(previous)
	defer monitor.Close()
	if state := monitor.InterfaceState(); state == nil || len(state.InterfaceIPs["lo"]) == 0 || state.DefaultRouteInterface != "lo" {
		return "missing loopback default-route snapshot"
	}

	entered, release := make(chan struct{}), make(chan struct{})
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer unblock()
	var blockNext atomic.Bool
	server := derpserver.New(key.NewNode(), logger.Discard)
	defer server.Close()
	handler := derpserver.Handler(server)
	httpServer := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/derp" && blockNext.CompareAndSwap(true, false) {
			close(entered)
			select {
			case <-release:
			case <-ctx.Done():
				return
			}
		}
		handler.ServeHTTP(w, r)
	}))
	httpServer.Config.TLSNextProto = map[string]func(*http.Server, *tls.Conn, http.Handler){}
	httpServer.StartTLS()
	defer httpServer.Close()
	// Release the held handler before httptest waits for it during cleanup.
	defer unblock()

	receiverKey := key.NewNode()
	receiver, err := derphttp.NewClient(receiverKey, httpServer.URL+"/derp", logger.Discard, monitor)
	if err != nil {
		return "receiver creation failed"
	}
	defer receiver.Close()
	roots := x509.NewCertPool()
	roots.AddCert(httpServer.Certificate())
	receiver.TLSConfig = &tls.Config{RootCAs: roots}
	stopReceiver := context.AfterFunc(ctx, func() { _ = receiver.Close() })
	defer stopReceiver()
	if err := receiver.Connect(ctx); err != nil {
		return "receiver connection failed"
	}
	if _, err := receiver.Recv(); err != nil {
		return "receiver registration failed"
	}

	listener := &permissionPacketListener{errno: syscall.EPERM}
	if mode == "broken-pipe" {
		listener.errno = syscall.EPIPE
	}
	connection, err := NewConn(Options{EventBus: bus, Logf: logger.Discard, NetMon: monitor,
		HealthTracker: health.NewTracker(bus), Metrics: new(usermetric.Registry),
		DisablePortMapper: true, TestOnlyPacketListener: listener})
	if err != nil {
		return "magicsock creation failed"
	}
	defer connection.Close()
	connection.netChecker.SkipExternalNetwork = true
	// Install the test identity without SetPrivateKey's asynchronous initial network probing.
	connection.privateKey = key.NewNode()
	connection.havePrivateKey.Store(true)
	connection.publicKeyAtomic.Store(connection.privateKey.Public())
	connection.everHadKey = true
	region := &tailcfg.DERPRegion{RegionID: 1, RegionCode: "loopback", Nodes: []*tailcfg.DERPNode{{
		Name: "1", RegionID: 1, HostName: "127.0.0.1", IPv4: "127.0.0.1", IPv6: "none",
		DERPPort: httpServer.Listener.Addr().(*net.TCPAddr).Port, STUNPort: -1, InsecureForTests: true,
	}}}
	connection.SetDERPMapWithoutReSTUN(&tailcfg.DERPMap{Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{1: region}})
	peer := &endpoint{c: connection, nodeID: 1, publicKey: receiverKey.Public(),
		bestAddr: addrQuality{epAddr: epAddr{ap: deniedUDP}}, trustBestAddrUntil: mono.Now().Add(time.Minute)}
	info := newPeerInfo(peer)
	connection.peerMap.byNodeKey[peer.publicKey], connection.peerMap.byNodeID[peer.nodeID] = info, info

	blockNext.Store(true)
	queue := connection.derpWriteChanForRegion(1, receiverKey.Public())
	if queue == nil {
		return "missing relay writer"
	}
	select {
	case <-entered:
	case <-ctx.Done():
		return "relay did not reach the held upgrade"
	}
	connection.mu.Lock()
	original := connection.activeDerp[1]
	connection.mu.Unlock()
	select {
	case <-connection.derpStarted:
		return "relay started before the held upgrade"
	default:
	}
	// DERP carries opaque WireGuard bytes: delivery must preserve this initiation-sized packet exactly.
	packet := make([]byte, device.MessageInitiationSize)
	packet[0], packet[len(packet)-1] = byte(device.MessageInitiationType), 91
	queue <- derpWriteRequest{pubKey: receiverKey.Public(), b: packet}
	if len(queue) != 1 {
		return "packet was not held in the initial relay queue"
	}
	if mode == "connected" {
		unblock()
		if !receivePermissionPacket(receiver, connection.privateKey.Public(), packet) {
			return "initial relay packet was not delivered"
		}
	}

	before := listener.binds.Load()
	if mode == "network-change" {
		connection.Rebind()
	} else {
		sent, err := connection.sendUDP(deniedUDP, []byte{1}, true, false)
		if sent || !errors.Is(err, listener.errno) {
			return "UDP denial was hidden"
		}
	}
	if listener.binds.Load() <= before {
		return "UDP sockets were not reopened"
	}
	peer.mu.Lock()
	trusted := peer.bestAddr.ap.IsValid() || peer.trustBestAddrUntil != 0
	peer.mu.Unlock()
	if trusted {
		return "UDP endpoint trust survived recovery"
	}
	connection.mu.Lock()
	current := connection.activeDerp[1]
	connection.mu.Unlock()
	if mode == "network-change" || mode == "broken-pipe" {
		if current.c == original.c {
			return "full rebind retained the pending relay"
		}
		return "ok"
	}
	if current.c != original.c || current.writeCh != queue {
		return "UDP permission recovery replaced the relay or its queue"
	}
	if mode != "connected" && len(queue) != 1 {
		return "UDP permission recovery discarded the queued packet"
	}
	before, last := listener.binds.Load(), connection.lastErrRebind.Load()
	_, _ = connection.sendUDP(deniedUDP, []byte{1}, true, false)
	if listener.binds.Load() != before || connection.lastErrRebind.Load() != last {
		return "UDP permission recovery bypassed the throttle"
	}
	if mode == "connected" {
		queue <- derpWriteRequest{pubKey: receiverKey.Public(), b: packet}
	} else {
		unblock()
	}
	if !receivePermissionPacket(receiver, connection.privateKey.Public(), packet) {
		return "relay did not deliver the preserved packet"
	}
	return "ok"
}

func receivePermissionPacket(receiver *derphttp.Client, source key.NodePublic, want []byte) bool {
	for {
		message, err := receiver.Recv()
		if err != nil {
			return false
		}
		if packet, ok := message.(derp.ReceivedPacket); ok {
			return packet.Source == source && bytes.Equal(packet.Data, want)
		}
	}
}

var deniedUDP = netip.MustParseAddrPort("192.0.2.1:9")

type permissionPacketListener struct {
	binds atomic.Int32
	errno syscall.Errno
}

func (p *permissionPacketListener) ListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	var config net.ListenConfig
	packet, err := config.ListenPacket(ctx, network, address)
	if err != nil {
		return nil, err
	}
	p.binds.Add(1)
	return &permissionPacketConn{UDPConn: packet.(*net.UDPConn), errno: p.errno}, nil
}

type permissionPacketConn struct {
	*net.UDPConn
	errno syscall.Errno
}

func (p *permissionPacketConn) WriteToUDPAddrPort(packet []byte, address netip.AddrPort) (int, error) {
	if address == deniedUDP {
		return 0, &net.OpError{Op: "write", Net: "udp", Err: p.errno}
	}
	return p.UDPConn.WriteToUDPAddrPort(packet, address)
}
