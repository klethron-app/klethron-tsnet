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
	"sync"
	"time"

	"tailscale.com/derp/derpserver"
	"tailscale.com/envknob"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/stun"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

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

	derpMap, stopDERP, err := startLoopbackDERP()
	if err != nil {
		return "loopback derp failed"
	}
	defer stopDERP()
	control := &testcontrol.Server{DERPMap: derpMap, Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Config.ErrorLog = log.New(io.Discard, "", 0)
	control.HTTPTestServer.Start()
	defer control.HTTPTestServer.Close()

	// Deferred closes run before the servers above stop.
	listener, listenerIP, problem := startTailnetNode(ctx, dir, control.HTTPTestServer.URL, "klethron-listener")
	defer listener.Close()
	if problem != "" {
		return problem
	}
	dialer, _, problem := startTailnetNode(ctx, dir, control.HTTPTestServer.URL, "klethron-dialer")
	defer dialer.Close()
	if problem != "" {
		return problem
	}
	ln, err := listener.Listen("tcp", ":7070")
	if err != nil {
		return "tailnet listen failed"
	}
	defer ln.Close()
	go serveEcho(ln)
	address := netip.AddrPortFrom(listenerIP, 7070).String()

	if problem := exchange(ctx, dialer, address); problem != "" {
		return problem
	}
	client, err := dialer.LocalClient()
	if err != nil {
		return "local client failed"
	}
	for {
		result, err := client.Ping(ctx, listenerIP, tailcfg.PingDisco)
		if err != nil || result.Err != "" {
			return "disco ping failed"
		}
		if relayOnly && (result.Endpoint != "" || result.DERPRegionID != 1) {
			return "unexpected direct path"
		}
		if relayOnly || (result.Endpoint != "" && result.DERPRegionID == 0) {
			break
		}
		select {
		case <-ctx.Done():
			return "no direct path"
		case <-time.After(100 * time.Millisecond):
		}
	}
	// Exchange again over the verified path.
	if problem := exchange(ctx, dialer, address); problem != "" {
		return problem
	}
	return "ok"
}

func startTailnetNode(ctx context.Context, dir, controlURL, hostname string) (*tsnet.Server, netip.Addr, string) {
	server := newServer()
	server.Dir = dir
	server.Hostname = hostname
	server.ControlURL = controlURL
	server.Store = new(mem.Store)
	server.Ephemeral = true
	status, err := server.Up(ctx)
	if err != nil {
		return server, netip.Addr{}, "node up failed"
	}
	for _, ip := range status.TailscaleIPs {
		if ip.Is4() {
			return server, ip, ""
		}
	}
	return server, netip.Addr{}, "no tailnet IPv4 address"
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
func exchange(ctx context.Context, node *tsnet.Server, address string) string {
	conn, err := node.Dial(ctx, "tcp", address)
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
