package main

import "C"

import (
	"strings"
	"sync"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// Monitors of started nodes. Updates hold the lock, so every monitor re-reads the latest values.
var (
	monitorsMu           sync.Mutex
	monitors             = make(map[*netmon.Monitor]*networkMonitor)
	lastNetworkHandle    int64
	lastNetworkInterface string
	reportedInterface    string
)

type networkMonitor struct {
	rebind bool       // Guarded by monitorsMu.
	mu     sync.Mutex // Serializes callbacks and waits for them before server.Close.
	closed bool
}

// Upstream netmon cannot discover Android's default route; TailnetNode's network callback reports
// the best non-VPN network instead, as "interface\x00IPv4 gateway" (either may be empty). Both are
// process-global upstream, so this updates every node.
//
//export klethron_set_default_network
func klethron_set_default_network(network *C.char, length C.int, handle C.longlong) {
	name, gateway, _ := strings.Cut(C.GoStringN(network, length), "\x00")
	monitorsMu.Lock()
	defer monitorsMu.Unlock()
	// A replacement Wi-Fi network can reuse the interface and addresses, which netmon cannot see.
	replaced := handle != 0 && lastNetworkHandle != 0 && int64(handle) != lastNetworkHandle &&
		name != "" && name == lastNetworkInterface
	if handle != 0 {
		lastNetworkHandle, lastNetworkInterface = int64(handle), name
	}
	reportedInterface = name
	netmon.UpdateLastKnownDefaultGateway(gateway)
	netmon.UpdateLastKnownDefaultRouteInterface(name)
	for monitor, registration := range monitors {
		if replaced {
			registration.rebind = true
		}
		// Forces change callbacks; a changed default interface makes the engine rebind.
		monitor.InjectEvent()
	}
}

// watchDefaultNetwork forwards default-network reports to a started node's monitor until the
// returned function is called. Reports to a closed monitor are harmless.
func watchDefaultNetwork(server *tsnet.Server) (unwatch func()) {
	monitor := server.Sys().NetMon.Get()
	// Start may have opened sockets before an identity-only replacement was reported.
	registration := &networkMonitor{rebind: true}
	unregister := monitor.RegisterChangeCallback(func(*netmon.ChangeDelta) {
		registration.mu.Lock()
		defer registration.mu.Unlock()
		if registration.closed {
			return
		}
		monitorsMu.Lock()
		ready := reportedInterface != "" && monitor.InterfaceState().DefaultRouteInterface == reportedInterface
		pending := ready && registration.rebind
		if pending {
			registration.rebind = false
		}
		monitorsMu.Unlock()
		if !pending {
			return
		}
		// Run socket work off the Android callback thread. Older netmon callbacks can arrive late,
		// so their delta cannot tell us whether the engine has handled this replacement already.
		connection := server.Sys().MagicSock.Get()
		connection.Rebind()
		connection.ReSTUN("android-network-replaced")
	})
	monitorsMu.Lock()
	defer monitorsMu.Unlock()
	monitors[monitor] = registration
	// Catch a report that arrived after the monitor read its initial state.
	monitor.InjectEvent()
	return func() {
		monitorsMu.Lock()
		delete(monitors, monitor)
		monitorsMu.Unlock()
		unregister()
		registration.mu.Lock()
		registration.closed = true
		registration.mu.Unlock()
	}
}
