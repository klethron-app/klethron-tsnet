//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

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

// checkProbeEnvironment returns a fixed diagnostic, or "" if a probe may start nodes in dir.
func checkProbeEnvironment(dir string) string {
	if !utf8.ValidString(dir) || strings.ContainsRune(dir, 0) || !filepath.IsAbs(dir) {
		return "invalid directory"
	}
	if buildfeatures.HasLogTail {
		return "logtail must be disabled"
	}
	// Refuse ambient auth configuration instead of risking an OAuth exchange.
	for _, name := range []string{"TS_AUTHKEY", "TS_AUTH_KEY", "TS_CLIENT_SECRET", "TS_CLIENT_ID", "TS_ID_TOKEN", "TS_AUDIENCE"} {
		if os.Getenv(name) != "" {
			return "unexpected authentication environment"
		}
	}
	return ""
}

// The DERP-only knob is process-global, so tailnet probes never overlap.
var tailnetProbeMu sync.Mutex

// This debug-only probe connects two in-process nodes through a loopback test control server
// and DERP/STUN server, then exchanges TCP data over the tailnet. No real tailnet, credentials
// or external network are involved. It returns fixed ASCII diagnostics only.
//
//export klethron_tailnet_probe
func klethron_tailnet_probe(path *C.char, length C.int, relayOnly C.int) *C.char {
	return C.CString(probeTailnet(C.GoStringN(path, length), relayOnly != 0))
}

func probeTailnet(dir string, relayOnly bool) string {
	if problem := checkProbeEnvironment(dir); problem != "" {
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
	if problem != "" {
		return problem
	}
	if problem := tailnet.exchange(ctx); problem != "" {
		return problem
	}
	if problem := tailnet.awaitPath(ctx, relayOnly); problem != "" {
		return problem
	}
	// Exchange again over the verified path.
	if problem := tailnet.exchange(ctx); problem != "" {
		return problem
	}
	return "ok"
}

// loopbackTailnet is a dialer and an echoing listener node, joined by in-process control and DERP servers.
type loopbackTailnet struct {
	dialer, listener *tsnet.Server
	listenerIP       netip.Addr
	closers          []func()
}

// startLoopbackTailnet returns a fixed diagnostic on failure. Close the result in either case.
func startLoopbackTailnet(ctx context.Context, dir string) (*loopbackTailnet, string) {
	t := new(loopbackTailnet)
	derpMap, stopDERP, err := startLoopbackDERP()
	if err != nil {
		return t, "loopback derp failed"
	}
	t.closers = append(t.closers, stopDERP)
	control := &testcontrol.Server{DERPMap: derpMap, Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Config.ErrorLog = log.New(io.Discard, "", 0)
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
	go serveEcho(ln)
	return t, ""
}

// close stops nodes before the servers they use.
func (t *loopbackTailnet) close() {
	for i := len(t.closers) - 1; i >= 0; i-- {
		t.closers[i]()
	}
}

func (t *loopbackTailnet) startNode(ctx context.Context, dir, controlURL, hostname string) (*tsnet.Server, netip.Addr, string) {
	server := newServer()
	server.Dir = dir
	server.Hostname = hostname
	server.ControlURL = controlURL
	server.Store = new(mem.Store)
	server.Ephemeral = true
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

// awaitPath waits until a disco ping uses a direct UDP path, or only DERP if relayOnly.
func (t *loopbackTailnet) awaitPath(ctx context.Context, relayOnly bool) string {
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

func serveEcho(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			_, _ = io.Copy(conn, conn)
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
	relay := derpserver.New(key.NewNode(), logger.Discard)
	server := httptest.NewUnstartedServer(derpserver.Handler(relay))
	server.Config.ErrorLog = log.New(io.Discard, "", 0)
	// DERP upgrades HTTP/1.1 connections.
	server.Config.TLSNextProto = map[string]func(*http.Server, *tls.Conn, http.Handler){}
	server.StartTLS()
	stunConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		server.Close()
		relay.Close()
		return nil, nil, err
	}
	go serveSTUN(stunConn)
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
			source = netip.AddrPortFrom(source.Addr().Unmap(), source.Port())
			_, _ = conn.WriteToUDPAddrPort(stun.Response(id, source), source)
		}
	}
}
