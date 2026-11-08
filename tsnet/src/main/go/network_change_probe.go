//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
	"tailscale.com/util/clientmetric"
)

// The instrumentation test changes the device's default network between these debug-only calls,
// reporting it through the public Java API. Results are fixed ASCII diagnostics.
var networkProbe struct {
	sync.Mutex
	tailnet *loopbackTailnet
	// Interface named by each node's latest rebinding change.
	rebound map[*netmon.Monitor]string
	// Process-global magicsock rebinds at the last checkpoint.
	rebinds int64
}

//export klethron_netchange_probe_start
func klethron_netchange_probe_start(path *C.char, length C.int) *C.char {
	return C.CString(startNetworkProbe(C.GoStringN(path, length)))
}

func startNetworkProbe(dir string) string {
	if problem := checkProbeEnvironment(dir); problem != "" {
		return problem
	}
	tailnetProbeMu.Lock()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	tailnet, problem := startLoopbackTailnet(ctx, dir)
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
	rebound := make(map[*netmon.Monitor]string)
	networkProbe.rebound = rebound
	networkProbe.rebinds = rebindCount()
	for _, node := range []*tsnet.Server{tailnet.dialer, tailnet.listener} {
		monitor := node.Sys().NetMon.Get()
		rebound[monitor] = ""
		tailnet.closers = append(tailnet.closers, monitor.RegisterChangeCallback(func(delta *netmon.ChangeDelta) {
			if delta.RebindLikelyRequired {
				networkProbe.Lock()
				defer networkProbe.Unlock()
				rebound[monitor] = delta.DefaultRouteInterface
			}
		}))
		// Without a host report, Android netmon has no default route and never rebinds.
		if monitor.InterfaceState().DefaultRouteInterface != "" {
			problem = "unreported default route"
		}
	}
	if problem != "" {
		stopNetworkProbe()
		return problem
	}
	return "ok"
}

// Waits until both nodes rebound for a change to the named default interface, then requires
// the magicsock rebinds and a tailnet exchange over a direct path re-established afterwards.
//
//export klethron_netchange_probe_await
func klethron_netchange_probe_await(name *C.char, length C.int) *C.char {
	return C.CString(awaitNetworkChange(C.GoStringN(name, length)))
}

func awaitNetworkChange(name string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for {
		networkProbe.Lock()
		if networkProbe.tailnet == nil {
			networkProbe.Unlock()
			return "not started"
		}
		// Both nodes' engines rebind; other paths may add rebinds, but not remove them.
		done := rebindCount() >= networkProbe.rebinds+2
		for monitor, rebound := range networkProbe.rebound {
			done = done && rebound == name && monitor.InterfaceState().DefaultRouteInterface == name
		}
		if done {
			networkProbe.rebinds = rebindCount()
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

func rebindCount() int64 {
	for _, metric := range clientmetric.Metrics() {
		if metric.Name() == "magicsock_rebind_calls" {
			return metric.Value()
		}
	}
	return 0
}

//export klethron_netchange_probe_stop
func klethron_netchange_probe_stop() {
	networkProbe.Lock()
	defer networkProbe.Unlock()
	stopNetworkProbe()
}

func stopNetworkProbe() {
	if networkProbe.tailnet != nil {
		networkProbe.tailnet.close()
		networkProbe.tailnet = nil
		tailnetProbeMu.Unlock()
	}
}
