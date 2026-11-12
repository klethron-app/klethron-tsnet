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
	monitorsMu sync.Mutex
	monitors   = make(map[*netmon.Monitor]struct{})
)

// Upstream netmon cannot discover Android's default route; TailnetNode's network callback reports
// the best non-VPN network instead, as "interface\x00IPv4 gateway" (either may be empty). Both are
// process-global upstream, so this updates every node.
//
//export klethron_set_default_network
func klethron_set_default_network(network *C.char, length C.int) {
	name, gateway, _ := strings.Cut(C.GoStringN(network, length), "\x00")
	monitorsMu.Lock()
	defer monitorsMu.Unlock()
	netmon.UpdateLastKnownDefaultGateway(gateway)
	netmon.UpdateLastKnownDefaultRouteInterface(name)
	for monitor := range monitors {
		// Forces change callbacks; a changed default interface makes the engine rebind.
		monitor.InjectEvent()
	}
}

// watchDefaultNetwork forwards default-network reports to a started node's monitor until the
// returned function is called. Reports to a closed monitor are harmless.
func watchDefaultNetwork(server *tsnet.Server) (unwatch func()) {
	monitor := server.Sys().NetMon.Get()
	monitorsMu.Lock()
	defer monitorsMu.Unlock()
	monitors[monitor] = struct{}{}
	// Catch a report that arrived after the monitor read its initial state.
	monitor.InjectEvent()
	return func() {
		monitorsMu.Lock()
		defer monitorsMu.Unlock()
		delete(monitors, monitor)
	}
}
