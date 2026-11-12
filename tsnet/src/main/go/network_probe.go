//go:build klethron_test

package main

import (
	"cmp"
	"encoding/hex"
	"fmt"
	"net"
	"strings"

	"tailscale.com/net/netmon"
)

// probeInterfaces returns Go's own discovery error, then a line per address from the registered
// getter, or the getter's error. It does not start a node or contact any endpoint.
func probeInterfaces(string) string {
	_, goErr := net.Interfaces()
	lines := []string{fmt.Sprint(goErr)}
	ifaces, err := netmon.GetInterfaceList()
	for _, iface := range ifaces {
		addrs, addrsErr := iface.Addrs()
		err = cmp.Or(err, addrsErr)
		for _, addr := range addrs {
			prefix := addr.(*net.IPNet)
			ones, _ := prefix.Mask.Size()
			lines = append(lines, fmt.Sprintf("%s|%d|%d|%t|%t|%t|%t|%s/%d",
				hex.EncodeToString([]byte(iface.Name)), iface.Index, iface.MTU,
				iface.Flags&net.FlagUp != 0, iface.Flags&net.FlagLoopback != 0,
				iface.Flags&net.FlagPointToPoint != 0, iface.Flags&net.FlagMulticast != 0,
				hex.EncodeToString(prefix.IP), ones))
		}
	}
	if err != nil {
		return "getter: " + err.Error()
	}
	return strings.Join(lines, "\n")
}
