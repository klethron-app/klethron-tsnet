//go:build klethron_test

package main

import (
	"cmp"
	"context"
	"sync"
	"time"

	"tailscale.com/tsnet"
)

// The instrumentation test changes the device's default network between these debug-only calls,
// reporting it through the public Java API. Results are fixed ASCII diagnostics.
var networkProbe struct {
	sync.Mutex
	tailnet *loopbackTailnet
}

func startNetworkProbe(dir string) string {
	if problem := checkProbeEnvironment(dir); problem != "" {
		return problem
	}
	tailnetProbeMu.Lock()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	tailnet, problem := startLoopbackTailnet(ctx, dir)
	if problem == "" {
		problem = tailnet.verify(ctx, false)
	}
	for _, node := range []*tsnet.Server{tailnet.dialer, tailnet.listener} {
		// Without a host report, Android netmon has no default route and never rebinds.
		if problem == "" && node.Sys().NetMon.Get().InterfaceState().DefaultRouteInterface != "" {
			problem = "unreported default route"
		}
	}
	networkProbe.Lock()
	defer networkProbe.Unlock()
	networkProbe.tailnet = tailnet
	if problem != "" {
		stopNetworkProbeLocked()
		return problem
	}
	return "ok"
}

// awaitNetworkChange waits for both nodes to finish rebinding to the named default interface, then
// exchanges tailnet data and requires a re-established direct path.
func awaitNetworkChange(name string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for {
		networkProbe.Lock()
		if networkProbe.tailnet == nil {
			networkProbe.Unlock()
			return "not started"
		}
		tailnet := networkProbe.tailnet
		networkProbe.Unlock()
		done := true
		for _, node := range []*tsnet.Server{tailnet.dialer, tailnet.listener} {
			rebound, ok := tailnet.rebound.Load(node)
			done = done && ok && rebound == name && node.Sys().NetMon.Get().InterfaceState().DefaultRouteInterface == name
		}
		if done {
			return cmp.Or(tailnet.verify(ctx, false), "ok")
		}
		select {
		case <-ctx.Done():
			return "no rebind"
		case <-time.After(50 * time.Millisecond):
		}
	}
}

func stopNetworkProbe(string) string {
	networkProbe.Lock()
	defer networkProbe.Unlock()
	stopNetworkProbeLocked()
	return ""
}

func stopNetworkProbeLocked() {
	if networkProbe.tailnet != nil {
		networkProbe.tailnet.close()
		networkProbe.tailnet = nil
		tailnetProbeMu.Unlock()
	}
}
