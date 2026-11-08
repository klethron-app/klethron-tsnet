//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"sort"

	"tailscale.com/net/netmon"
	"tailscale.com/types/logger"
	"tailscale.com/util/eventbus"
)

// This bridge is linked only into debug builds. It does not start a tsnet server,
// read node state, or contact any endpoint.
//
//export klethron_network_probe
func klethron_network_probe() *C.char {
	result := struct {
		Error      string
		GoError    string
		Interfaces []string
	}{}
	if _, err := net.Interfaces(); err != nil {
		result.GoError = err.Error()
	}
	ifaces, err := netmon.GetInterfaceList()
	if err == nil {
		for _, iface := range ifaces {
			var addrs []net.Addr
			addrs, err = iface.Addrs()
			if err != nil {
				break
			}
			for _, addr := range addrs {
				prefix := addr.(*net.IPNet)
				ones, _ := prefix.Mask.Size()
				result.Interfaces = append(result.Interfaces, fmt.Sprintf("%s|%d|%d|%t|%t|%t|%t|%s/%d",
					hex.EncodeToString([]byte(iface.Name)), iface.Index, iface.MTU,
					iface.Flags&net.FlagUp != 0, iface.Flags&net.FlagLoopback != 0,
					iface.Flags&net.FlagPointToPoint != 0, iface.Flags&net.FlagMulticast != 0,
					hex.EncodeToString(prefix.IP), ones))
			}
		}
	}
	if err == nil {
		bus := eventbus.New()
		defer bus.Close()
		var monitor *netmon.Monitor
		monitor, err = netmon.New(bus, logger.Discard)
		if err == nil {
			monitor.Start()
			err = monitor.Close()
		}
	}
	if err != nil {
		result.Error = err.Error()
	}
	sort.Strings(result.Interfaces)
	data, _ := json.Marshal(result) // Strings and slices only; cannot fail.
	return C.CString(string(data))
}
