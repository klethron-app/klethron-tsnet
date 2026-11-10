package main

/*
#include <stdint.h>
*/
import "C"

import (
	"net/netip"
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
// it instead. The interface name and IPv4 gateway are process-global upstream, so this updates
// every node.
//
//export klethron_set_default_network
func klethron_set_default_network(name *C.char, length C.int, gateway C.uint32_t) {
	gw := ""
	if gateway != 0 {
		gw = netip.AddrFrom4([4]byte{byte(gateway >> 24), byte(gateway >> 16), byte(gateway >> 8), byte(gateway)}).String()
	}
	setDefaultNetwork(C.GoStringN(name, length), gw)
}

func setDefaultNetwork(name, gateway string) {
	monitorsMu.Lock()
	defer monitorsMu.Unlock()
	netmon.UpdateLastKnownDefaultGateway(gateway)
	netmon.UpdateLastKnownDefaultRouteInterface(name)
	for monitor := range monitors {
		// Forces change callbacks; a changed default interface makes the engine rebind.
		monitor.InjectEvent()
	}
}

// watchDefaultNetwork forwards default-network reports to a started node until the returned
// function is called, which must happen before the node closes.
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
