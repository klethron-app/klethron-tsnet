//go:build klethron_test

package main

import (
	"cmp"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"

	"tailscale.com/net/netmon"
)

// probeInterfaces reports interfaces from the registered getter and Go's own discovery error. It
// does not start a node or contact any endpoint.
func probeInterfaces(string) string {
	result := struct {
		Error      string
		GoError    string
		Interfaces []string
	}{}
	if _, err := net.Interfaces(); err != nil {
		result.GoError = err.Error()
	}
	ifaces, err := netmon.GetInterfaceList()
	for _, iface := range ifaces {
		addrs, addrsErr := iface.Addrs()
		err = cmp.Or(err, addrsErr)
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
	if err != nil {
		result.Error = err.Error()
	}
	data, _ := json.Marshal(result) // Strings and slices only; cannot fail.
	return string(data)
}
