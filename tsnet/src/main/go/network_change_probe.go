//go:build klethron_test

package main

import (
	"cmp"
	"context"
	"sync/atomic"
	"time"

	"tailscale.com/tsnet"
)

// The instrumentation test changes the device's default network between these calls, reporting it
// through the public Java API. The started tailnet holds tailnetProbeMu until stopNetworkProbe.
var networkTailnet atomic.Pointer[loopbackTailnet]

func startNetworkProbe(string) string {
	if problem := checkProbeEnvironment(); problem != "" {
		return problem
	}
	tailnetProbeMu.Lock()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	tailnet, problem := startLoopbackTailnet(ctx)
	if problem == "" {
		problem = tailnet.verify(ctx, false)
	}
	for _, node := range []*tsnet.Server{tailnet.dialer, tailnet.listener} {
		// Without a host report, Android netmon has no default route and never rebinds.
		if problem == "" && node.Sys().NetMon.Get().InterfaceState().DefaultRouteInterface != "" {
			problem = "unreported default route"
		}
	}
	if problem != "" {
		tailnet.close()
		tailnetProbeMu.Unlock()
		return problem
	}
	networkTailnet.Store(tailnet)
	return "ok"
}

// awaitNetworkChange waits for both nodes to finish rebinding to the named default interface, then
// exchanges tailnet data and requires a re-established direct path.
func awaitNetworkChange(name string) string {
	tailnet := networkTailnet.Load()
	if tailnet == nil {
		return "not started"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for {
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
	if tailnet := networkTailnet.Swap(nil); tailnet != nil {
		tailnet.close()
		tailnetProbeMu.Unlock()
	}
	return ""
}

func resetNetworkRebinds(string) string {
	if tailnet := networkTailnet.Load(); tailnet != nil {
		tailnet.rebound.Clear()
		return "ok"
	}
	return "not started"
}

func unchangedNetworkRebinds(string) string {
	tailnet := networkTailnet.Load()
	if tailnet == nil {
		return "not started"
	}
	// Allow netmon's one-second debounce to deliver the repeated properties.
	time.Sleep(1500 * time.Millisecond)
	for _, node := range []*tsnet.Server{tailnet.dialer, tailnet.listener} {
		if _, ok := tailnet.rebound.Load(node); ok {
			return "unexpected rebind"
		}
	}
	return "ok"
}
