//go:build klethron_test

package main

import (
	"bytes"
	"cmp"
	"context"
	"crypto/rand"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"slices"
	"sync"
	"time"

	"tailscale.com/derp/derpserver"
	"tailscale.com/envknob"
	"tailscale.com/feature/buildfeatures"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/stun"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

// checkProbeEnvironment returns a fixed diagnostic, or "" if a probe may start nodes.
func checkProbeEnvironment() string {
	if buildfeatures.HasLogTail {
		return "logtail must be disabled"
	}
	// tsnet would send an ambient auth key to control.
	for _, name := range []string{"TS_AUTHKEY", "TS_AUTH_KEY"} {
		if os.Getenv(name) != "" {
			return "unexpected authentication environment"
		}
	}
	return ""
}

// The DERP-only knob is process-global, so tailnet probes never overlap.
var tailnetProbeMu sync.Mutex

// probeTailnet connects two in-process nodes through a loopback test control server and DERP/STUN
// server, then exchanges TCP data over the tailnet. No real tailnet, credentials or external
// network are involved. It returns fixed ASCII diagnostics only.
func probeTailnet(dir string, relayOnly bool) string {
	if problem := checkProbeEnvironment(); problem != "" {
		return problem
	}
	tailnetProbeMu.Lock()
	defer tailnetProbeMu.Unlock()
	if relayOnly {
		// Nodes created while set bind no UDP sockets, so peers can only meet through DERP.
		envknob.Setenv("TS_DEBUG_ALWAYS_USE_DERP", "true")
		defer envknob.Setenv("TS_DEBUG_ALWAYS_USE_DERP", "")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	tailnet, problem := startLoopbackTailnet(ctx, dir)
	defer tailnet.close()
	if problem == "" {
		problem = tailnet.verify(ctx, relayOnly)
	}
	if problem == "" {
		problem = tailnet.exchange(ctx) // Again over the verified path.
	}
	return cmp.Or(problem, "ok")
}

// loopbackTailnet is a dialer and an echoing listener node, joined by in-process control and DERP servers.
type loopbackTailnet struct {
	dialer, listener *tsnet.Server
	listenerIP       netip.Addr
	closers          []func()
	rebound          sync.Map // *tsnet.Server -> interface named by its latest rebind
}

// startLoopbackTailnet returns a fixed diagnostic on failure. Close the result in either case.
func startLoopbackTailnet(ctx context.Context, dir string) (*loopbackTailnet, string) {
	t := new(loopbackTailnet)
	derpMap, stopDERP, err := startLoopbackDERP()
	if err != nil {
		return t, "loopback derp failed"
	}
	t.closers = append(t.closers, stopDERP)
	control := &testcontrol.Server{DERPMap: derpMap}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.closers = append(t.closers, control.HTTPTestServer.Close)

	var problem string
	if t.listener, t.listenerIP, problem = t.startNode(ctx, dir, control.HTTPTestServer.URL, "klethron-listener"); problem != "" {
		return t, problem
	}
	if t.dialer, _, problem = t.startNode(ctx, dir, control.HTTPTestServer.URL, "klethron-dialer"); problem != "" {
		return t, problem
	}
	ln, err := t.listener.Listen("tcp", ":7070")
	if err != nil {
		return t, "tailnet listen failed"
	}
	t.closers = append(t.closers, func() { _ = ln.Close() })
	go serve(ln, func(conn net.Conn) { _, _ = io.Copy(conn, conn) })
	return t, ""
}

// close stops nodes before the servers they use.
func (t *loopbackTailnet) close() {
	for _, closer := range slices.Backward(t.closers) {
		closer()
	}
}

func (t *loopbackTailnet) startNode(ctx context.Context, dir, controlURL, hostname string) (*tsnet.Server, netip.Addr, string) {
	server := newServer()
	server.Logf = func(format string, args ...any) {
		// Observe successful UDP rebinding without formatting or retaining log messages.
		if format == "Rebind; defIf=%q, ips=%v" {
			t.rebound.Store(server, args[0])
		}
	}
	server.Dir, server.Hostname, server.ControlURL = dir, hostname, controlURL
	server.Store, server.Ephemeral = new(mem.Store), true
	t.closers = append(t.closers, func() { _ = server.Close() })
	status, err := server.Up(ctx)
	if err != nil {
		return server, netip.Addr{}, "node up failed"
	}
	t.closers = append(t.closers, watchDefaultNetwork(server))
	for _, ip := range status.TailscaleIPs {
		if ip.Is4() {
			return server, ip, ""
		}
	}
	return server, netip.Addr{}, "no tailnet IPv4 address"
}

// verify exchanges data, which waits for the peer, then waits until a disco ping uses a direct UDP
// path, or only DERP if relayOnly.
func (t *loopbackTailnet) verify(ctx context.Context, relayOnly bool) string {
	if problem := t.exchange(ctx); problem != "" {
		return problem
	}
	client, err := t.dialer.LocalClient()
	if err != nil {
		return "local client failed"
	}
	for {
		result, err := client.Ping(ctx, t.listenerIP, tailcfg.PingDisco)
		if err != nil || result.Err != "" {
			return "disco ping failed"
		}
		if relayOnly && (result.Endpoint != "" || result.DERPRegionID != 1) {
			return "unexpected direct path"
		}
		if relayOnly || (result.Endpoint != "" && result.DERPRegionID == 0) {
			return ""
		}
		select {
		case <-ctx.Done():
			return "no direct path"
		case <-time.After(100 * time.Millisecond):
		}
	}
}

// serve handles each connection that ln accepts on its own goroutine, then closes it.
func serve(ln net.Listener, handle func(net.Conn)) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			handle(conn)
		}()
	}
}

// exchange sends enough data to need many segments and window updates, then requires an exact echo.
func (t *loopbackTailnet) exchange(ctx context.Context) string {
	conn, err := t.dialer.Dial(ctx, "tcp", netip.AddrPortFrom(t.listenerIP, 7070).String())
	if err != nil {
		return "tailnet dial failed"
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	sent := make([]byte, 256<<10)
	_, _ = rand.Read(sent)
	written := make(chan error, 1)
	go func() {
		_, err := conn.Write(sent)
		written <- err
	}()
	received := make([]byte, len(sent))
	if _, err := io.ReadFull(conn, received); err != nil {
		return "tailnet read failed"
	}
	if err := <-written; err != nil {
		return "tailnet write failed"
	}
	if !bytes.Equal(sent, received) {
		return "tailnet data mismatch"
	}
	return ""
}

// startLoopbackDERP serves one DERP region and STUN on 127.0.0.1 with a self-signed certificate.
// InsecureForTests makes clients skip verification of that certificate: this is not a TLS proof.
func startLoopbackDERP() (*tailcfg.DERPMap, func(), error) {
	stunConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return nil, nil, err
	}
	go serveSTUN(stunConn)
	relay := derpserver.New(key.NewNode(), logger.Discard)
	server := httptest.NewUnstartedServer(derpserver.Handler(relay))
	// DERP upgrades HTTP/1.1 connections.
	server.Config.TLSNextProto = map[string]func(*http.Server, *tls.Conn, http.Handler){}
	server.StartTLS()
	stop := func() {
		_ = stunConn.Close()
		server.CloseClientConnections()
		server.Close()
		_ = relay.Close()
	}
	node := &tailcfg.DERPNode{
		Name:             "1",
		RegionID:         1,
		HostName:         "127.0.0.1",
		IPv4:             "127.0.0.1",
		IPv6:             "none",
		STUNPort:         stunConn.LocalAddr().(*net.UDPAddr).Port,
		DERPPort:         server.Listener.Addr().(*net.TCPAddr).Port,
		InsecureForTests: true,
		STUNTestIP:       "127.0.0.1",
	}
	return &tailcfg.DERPMap{Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{
		1: {RegionID: 1, RegionCode: "loopback", RegionName: "Loopback", Nodes: []*tailcfg.DERPNode{node}},
	}}, stop, nil
}

func serveSTUN(conn *net.UDPConn) {
	buffer := make([]byte, 1500)
	for {
		n, source, err := conn.ReadFromUDPAddrPort(buffer)
		if err != nil {
			return
		}
		if id, err := stun.ParseBindingRequest(buffer[:n]); err == nil {
			_, _ = conn.WriteToUDPAddrPort(stun.Response(id, source), source)
		}
	}
}
