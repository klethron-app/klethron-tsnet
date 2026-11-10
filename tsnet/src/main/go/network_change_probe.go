//go:build klethron_test

package main

import (
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
	// Interface named by each node's latest rebinding change.
	rebound *sync.Map // *tsnet.Server -> string
}

func startNetworkProbe(dir string) string {
	if problem := checkProbeEnvironment(dir); problem != "" {
		return problem
	}
	tailnetProbeMu.Lock()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	rebound := new(sync.Map)
	tailnet, problem := startLoopbackTailnet(ctx, dir, func(node *tsnet.Server, name string) {
		rebound.Store(node, name)
	})
	// The first exchange waits for the peer; then require a direct path.
	if problem == "" {
		problem = tailnet.exchange(ctx)
	}
	if problem == "" {
		problem = tailnet.awaitPath(ctx, false)
	}
	if problem != "" {
		tailnet.close()
		tailnetProbeMu.Unlock()
		return problem
	}
	networkProbe.Lock()
	defer networkProbe.Unlock()
	networkProbe.tailnet = tailnet
	networkProbe.rebound = rebound
	for _, node := range []*tsnet.Server{tailnet.dialer, tailnet.listener} {
		monitor := node.Sys().NetMon.Get()
		// Without a host report, Android netmon has no default route and never rebinds.
		if monitor.InterfaceState().DefaultRouteInterface != "" {
			problem = "unreported default route"
		}
	}
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
		done := true
		for _, node := range []*tsnet.Server{networkProbe.tailnet.dialer, networkProbe.tailnet.listener} {
			rebound, ok := networkProbe.rebound.Load(node)
			done = done && ok && rebound == name && node.Sys().NetMon.Get().InterfaceState().DefaultRouteInterface == name
		}
		tailnet := networkProbe.tailnet
		networkProbe.Unlock()
		if done {
			if problem := tailnet.exchange(ctx); problem != "" {
				return problem
			}
			if problem := tailnet.awaitPath(ctx, false); problem != "" {
				return problem
			}
			return "ok"
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
