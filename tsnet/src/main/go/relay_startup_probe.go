//go:build klethron_test

package main

import (
	"context"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn/store/mem"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/util/klethrontiming"
)

// Hold network probing at its existing debug span and require the fresh control map to
// establish the home relay. An eventual echo alone would also pass without the seeding call.
func probeFreshRelayHint(string) string {
	if problem := checkProbeEnvironment(); problem != "" {
		return problem
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	control := &testcontrol.Server{ModifyFirstMapResponse: func(response *tailcfg.MapResponse, _ *tailcfg.MapRequest) {
		response.Node.HomeDERP = 1
	}}
	stop, err := startLoopbackControl(control)
	if err != nil {
		return "loopback control failed"
	}
	defer stop()
	server := newServer()
	server.Store, server.Ephemeral = new(mem.Store), true
	server.Hostname, server.ControlURL = "klethron-relay-startup", control.HTTPTestServer.URL
	defer server.Close()
	probing, connected, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
	defer close(release) // Release netcheck before Server.Close waits for it.
	var probeOnce, connectedOnce sync.Once
	server.Logf = func(format string, args ...any) {
		// Observe only fixed markers; never format or forward ordinary logs.
		if strings.HasSuffix(format, klethrontiming.Marker) && len(args) == 3 && args[0] == "netcheck" && args[1] == 0 {
			probeOnce.Do(func() { close(probing) })
			select {
			case <-release:
			case <-ctx.Done():
			}
		}
		if format == "magicsock: derp-%d connected; connGen=%v" {
			connectedOnce.Do(func() { close(connected) })
		}
	}
	if err := server.Start(); err != nil {
		return "node start failed"
	}
	select {
	case <-probing:
	case <-ctx.Done():
		return "netcheck did not reach the hold"
	}
	select {
	case <-connected:
	case <-ctx.Done():
		return "fresh control did not connect the relay before netcheck"
	}
	if ctx.Err() != nil {
		return "fresh control did not connect the relay before netcheck"
	}
	if server.Sys().MagicSock.Get().GetLastNetcheckReport(ctx) != nil {
		return "netcheck completed before the relay connection"
	}
	if server.Status().Self.Relay != "loopback" {
		return "fresh hint did not select the connected home"
	}
	return "ok"
}
