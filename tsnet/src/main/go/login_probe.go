//go:build klethron_test

package main

import (
	"context"
	"fmt"
	"io"
	"maps"
	"net"
	"net/http"
	"net/netip"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/tailcfg"
	"tailscale.com/tailcfg/nodecap"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/util/set"
)

// A loopback test control server for tests of the public Java API, which start nodes. With
// interactive login, LoginTest completes it in place of a browser. Otherwise nodes join at once,
// with MagicDNS, and DialTest adds an in-memory peer. No real tailnet is involved.
var loginProbe struct {
	sync.Mutex
	control  *testcontrol.Server
	stop     func()
	received chan int64    // Of the peer's greeting and stalled ports, per connection the dialer closed.
	release  chan struct{} // Closed to let the peer's stalled port read.
	flooded  atomic.Int64  // Bytes the peer's flooding port has written.
	expired  set.Set[key.NodePublic]
	removed  atomic.Bool // Map requests fail as for nodes deleted from the tailnet.
	rejected atomic.Bool // Registration requests fail, including logout.
}

// startControl starts the control server, requiring interactive login if interactive is "true".
// It returns the control URL, or "" on failure.
func startControl(interactive string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	control := &testcontrol.Server{RequireAuth: interactive == "true"}
	if interactive == "locked" {
		lockExpectSaved.Store(false)
		lockMissingHead.Store(0)
		control.ModifyFirstMapResponse = func(response *tailcfg.MapResponse, request *tailcfg.MapRequest) {
			if response.Node.CapMap == nil {
				response.Node.CapMap = make(tailcfg.NodeCapMap)
			}
			response.Node.CapMap[nodecap.TailnetLock] = nil
			control.SetNodeCapMap(request.NodeKey, response.Node.CapMap)
			if lockExpectSaved.Load() && request.TKAHead == "" {
				lockMissingHead.Add(1)
			}
		}
	}
	if !control.RequireAuth {
		control.DNSConfig = &tailcfg.DNSConfig{Proxied: true}
		control.MagicDNSDomain = "tail-scale.ts.net" // As in upstream's tsnet tests.
	}
	// Real control answers map requests from deleted nodes with 404, as in upstream's tests.
	control.MaybeRejectRequest = func(request *http.Request) (int, string, string) {
		if request.URL.Path == "/machine/map" && loginProbe.removed.Load() {
			return http.StatusNotFound, "", "node not found"
		}
		if request.URL.Path == "/machine/register" && loginProbe.rejected.Load() {
			// Registration errors use a JSON response with HTTP 200, as in testcontrol.
			return http.StatusOK, "", `{"Error":"invalid authkey"}`
		}
		return 0, "", ""
	}
	stop, err := startLoopbackControl(control)
	if err != nil {
		return ""
	}
	loginProbe.control, loginProbe.stop = control, stop
	return control.HTTPTestServer.URL
}

// Check the retired key: cancelled registrations can leave unrelated nodes in testcontrol.
func probeNonInteractiveRotation(string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	control := new(testcontrol.Server)
	stop, err := startLoopbackControl(control)
	if err != nil {
		return "control failed"
	}
	defer stop()
	fixture := new(loopbackTailnet)
	defer fixture.close()
	server, _, problem := fixture.startNode(ctx, control.HTTPTestServer.URL, "rotation")
	if problem != "" {
		return problem
	}
	previous := server.Status().Self.PublicKey
	client, err := server.LocalClient()
	if err != nil || client.StartLoginInteractive(ctx) != nil {
		return "login failed"
	}
	for ctx.Err() == nil {
		if server.Status().Self.PublicKey != previous && control.Node(previous) == nil {
			return "ok"
		}
		time.Sleep(10 * time.Millisecond)
	}
	return "rotation did not retire the previous node key"
}

// startPeer starts an in-memory peer that echoes on port 7070. On port 7071
// it sends a greeting, closes its side, then reports how many bytes it received until the dialer
// closed. On port 7073 it writes floodBytes of the probe pattern, counting progress, then closes.
// On port 7074 it reads nothing until released, then reports how many bytes of the probe pattern
// it received until the dialer closed, or -3 at the first other byte. It returns the peer's
// tailnet IPv4 address, or a diagnostic on failure. Mode "dns" also starts the split-DNS fixture.
func startPeer(mode string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	peer := new(loopbackTailnet)
	stop := loginProbe.stop
	loginProbe.stop = func() {
		peer.close()
		stop()
	}
	server, ip, problem := peer.startNode(ctx, loginProbe.control.HTTPTestServer.URL, "klethron-peer")
	if problem != "" {
		return ""
	}
	received := make(chan int64, 16)
	release := make(chan struct{})
	loginProbe.received, loginProbe.release = received, release
	loginProbe.flooded.Store(0)
	handlers := map[string]func(net.Conn){
		":7070": func(conn net.Conn) { _, _ = io.Copy(conn, conn) },
		":7071": func(conn net.Conn) {
			_, _ = conn.Write([]byte("klethron\n"))
			_ = conn.(interface{ CloseWrite() error }).CloseWrite()
			received <- countReceived(conn, false)
		},
		":7073": func(conn net.Conn) {
			chunk := make([]byte, 16<<10)
			for written := 0; written < floodBytes; written += len(chunk) {
				for i := range chunk {
					chunk[i] = byte((written + i) % 251)
				}
				if _, err := conn.Write(chunk); err != nil {
					return
				}
				loginProbe.flooded.Add(int64(len(chunk)))
			}
		},
		":7074": func(conn net.Conn) {
			<-release
			received <- countReceived(conn, true)
		},
	}
	for port, handle := range handlers {
		ln, err := server.Listen("tcp", port)
		if err != nil {
			return ""
		}
		peer.closers = append(peer.closers, func() { _ = ln.Close() })
		go serve(ln, handle)
	}
	if mode == "dns" {
		if err := startPeerDNS(peer, server, ip, loginProbe.control); err != nil {
			return "DNS peer failed"
		}
	}
	return ip.String()
}

// floodBytes exceeds what the dialer and peer can buffer for one connection: netstack's 8 MiB
// receive and 6 MiB send buffers, plus the relayed socket pair's.
const floodBytes = 48 << 20

// countReceived reads conn to its end, returning the bytes read, -1 after a read error, or with
// pattern, -3 at the first byte that differs from the probe pattern.
func countReceived(conn net.Conn, pattern bool) int64 {
	buffer := make([]byte, 64<<10)
	var total int64
	for {
		n, err := conn.Read(buffer)
		for i, b := range buffer[:n] {
			if pattern && b != byte((total+int64(i))%251) {
				return -3
			}
		}
		total += int64(n)
		if err == io.EOF {
			return total
		}
		if err != nil {
			return -1
		}
	}
}

func flooded(string) string {
	return strconv.FormatInt(loginProbe.flooded.Load(), 10)
}

// releaseStalled lets the peer's stalled port read, once per peer.
func releaseStalled(string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.release != nil {
		close(loginProbe.release)
		loginProbe.release = nil
	}
	return ""
}

// awaitReceived returns the count reported by the next greeting or stalled connection that the
// dialer closed, or -2 if none closed within 30 seconds.
func awaitReceived(string) string {
	loginProbe.Lock()
	reports := loginProbe.received
	loginProbe.Unlock()
	select {
	case received := <-reports:
		return strconv.FormatInt(received, 10)
	case <-time.After(30 * time.Second):
		return "-2"
	}
}

// withControl calls probe with the started control server under the probe lock, or returns
// "not started".
func withControl(probe func(control *testcontrol.Server, argument string) string) func(string) string {
	return func(argument string) string {
		loginProbe.Lock()
		defer loginProbe.Unlock()
		if loginProbe.control == nil {
			return "not started"
		}
		return probe(loginProbe.control, argument)
	}
}

// setPeerOnline sends the other nodes a patch marking the in-memory peer online, as control does
// for presence changes, without storing it. It returns whether the peer is registered.
func setPeerOnline(control *testcontrol.Server, _ string) string {
	all := control.AllNodes()
	i := slices.IndexFunc(all, func(node *tailcfg.Node) bool { return node.Hostinfo.Hostname() == "klethron-peer" })
	if i < 0 {
		return "false"
	}
	patch := &tailcfg.MapResponse{PeersChangedPatch: []*tailcfg.PeerChange{{NodeID: all[i].ID, Online: new(true)}}}
	for _, node := range slices.Delete(all, i, i+1) {
		control.AddRawMapResponse(node.Key, patch)
	}
	return "true"
}

// addPeers registers three unreachable devices: an offline Linux device named klethron-other, with
// addresses 100.101.0.1 and fd7a:115c:a1e0::101:1 and an OpenSSH host key and one containing a
// comma, a sharee node and a Mullvad exit node.
func addPeers(control *testcontrol.Server, _ string) string {
	add := func(id int, name string, hostinfo *tailcfg.Hostinfo, addresses ...string) {
		node := &tailcfg.Node{
			ID: tailcfg.NodeID(1000 + id), StableID: tailcfg.StableNodeID(fmt.Sprintf("KLETHRON%d", id)),
			Name: name, Key: key.NewNode().Public(), Machine: key.NewMachine().Public(),
			DiscoKey: key.NewDisco().Public(), MachineAuthorized: true, Hostinfo: hostinfo.View(),
		}
		for _, address := range addresses {
			ip := netip.MustParseAddr(address)
			prefix := netip.PrefixFrom(ip, ip.BitLen())
			node.Addresses = append(node.Addresses, prefix)
			node.AllowedIPs = append(node.AllowedIPs, prefix)
		}
		control.UpdateNode(node)
	}
	// IPv6 first, which the library reorders. It passes host keys through without parsing them.
	other := &tailcfg.Hostinfo{OS: "linux", SSH_HostKeys: []string{
		"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\n", "ssh-rsa AAAA,invalid"}}
	add(1, "klethron-other.tail-scale.ts.net.", other, "fd7a:115c:a1e0::101:1", "100.101.0.1")
	add(2, "klethron-sharee.tail-scale.ts.net.", &tailcfg.Hostinfo{OS: "linux", ShareeNode: true}, "100.101.0.2")
	add(3, "us-nyc-wg-301.mullvad.ts.net.", &tailcfg.Hostinfo{OS: "linux"}, "100.101.0.3")
	return ""
}

// completeLogin acts as the user's browser, approving a login URL, and returns "ok". Like real
// control, the approval gives a re-login's new node key a fresh expiry.
func completeLogin(control *testcontrol.Server, url string) string {
	// testcontrol stages a re-login's new key as a copy of the expired node.
	for _, node := range control.AllNodes() {
		if !node.KeyExpiry.IsZero() && !loginProbe.expired.Contains(node.Key) {
			node.KeyExpiry = time.Now().Add(24 * time.Hour)
			control.UpdateNode(node)
		}
	}
	if !control.CompleteAuth(url) {
		return "not completed"
	}
	return "ok"
}

// expireNodes expires the node keys of all registered nodes, as when their key expiry passes or
// an administrator expires them. It returns the number of devices, as approveMachines counts them.
func expireNodes(control *testcontrol.Server, _ string) string {
	loginProbe.expired.Make()
	devices := make(set.Set[key.MachinePublic])
	for _, node := range control.AllNodes() {
		node.KeyExpiry = time.Now().Add(-time.Minute)
		loginProbe.expired.Add(node.Key)
		devices.Add(node.Machine)
		control.UpdateNode(node)
	}
	return strconv.Itoa(len(devices))
}

// renameNodes renames all registered nodes to name, as an administrator renames a device.
func renameNodes(control *testcontrol.Server, name string) string {
	for _, node := range control.AllNodes() {
		node.Name = name
		control.UpdateNode(node)
	}
	return ""
}

// removeNodes makes control answer map requests as for nodes deleted from the tailnet if removed
// is "true", or no longer. A running node notices once it starts a new map request.
func removeNodes(removed string) string {
	loginProbe.removed.Store(removed == "true")
	return ""
}

// rejectRegistrations makes control reject every registration request, including logouts, with an
// error if reject is "true", or no longer. Stopping the server would not: nodes keep their hijacked
// Noise connection.
func rejectRegistrations(reject string) string {
	loginProbe.rejected.Store(reject == "true")
	return ""
}

// requireMachineAuth makes control require an administrator to approve new devices. testcontrol
// reads the field without locking; set it before nodes register.
func requireMachineAuth(control *testcontrol.Server, _ string) string {
	control.RequireMachineAuth = true
	return ""
}

// approveMachines acts as a tailnet administrator, approving all registered devices. It returns
// the number of devices approved. testcontrol records a node for each key's first registration
// request, so one device can have several: its first key, abandoned if Start's interactive login
// begins before that registration completes, and a rotation's staged key. They share a machine key.
func approveMachines(control *testcontrol.Server, _ string) string {
	approved := make(set.Set[key.MachinePublic])
	url := control.HTTPTestServer.URL
	for _, node := range control.AllNodes() {
		if control.CompleteDeviceApproval(url, url+"/admin", &node.Key) {
			approved.Add(node.Machine)
		}
	}
	return strconv.Itoa(len(approved))
}

// hostnames returns the sorted hostnames that registered devices last reported to control,
// separated by newlines. Like approveMachines, it counts devices by machine key, but keeps a
// device's conflicting hostnames visible to assertions.
func hostnames(control *testcontrol.Server, _ string) string {
	names := make(map[string]string) // By machine key and hostname.
	for _, node := range control.AllNodes() {
		names[node.Machine.String()+" "+node.Hostinfo.Hostname()] = node.Hostinfo.Hostname()
	}
	return strings.Join(slices.Sorted(maps.Values(names)), "\n")
}

func stopControl(string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control != nil {
		if loginProbe.release != nil {
			close(loginProbe.release) // Ends stalled handlers once their connections close.
		}
		loginProbe.stop()
		loginProbe.control = nil
		loginProbe.received, loginProbe.release = nil, nil
		loginProbe.expired = nil
		loginProbe.removed.Store(false)
		loginProbe.rejected.Store(false)
	}
	return ""
}

// setAuthKeyEnvironment sets TS_AUTHKEY if enabled is "true", otherwise unsets it. Go copies the
// process environment when the library loads; Android's Os.setenv cannot change what upstream
// reads afterwards, but Go's own os.Setenv does.
func setAuthKeyEnvironment(enabled string) string {
	if enabled == "true" {
		_ = os.Setenv("TS_AUTHKEY", "tskey-test-not-a-key")
	} else {
		_ = os.Unsetenv("TS_AUTHKEY")
	}
	return ""
}
