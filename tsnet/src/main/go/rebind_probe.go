//go:build klethron_test

package main

import "C"

import (
	"net/netip"
	"strconv"
	"strings"

	"tailscale.com/wgengine/magicsock"
)

func probePeerUDPDenied(argument string) string {
	fields := strings.Split(argument, "|")
	id, _ := strconv.ParseInt(fields[0], 10, 64)
	n := lookupNode(C.longlong(id))
	return magicsock.KlethronProbePeerUDPDenied(fields[1], netip.MustParseAddr(fields[2]),
		netip.MustParseAddr(fields[3]), n.store.localNetworkDenied)
}

func probePermissionRecovery(mode string) string {
	tailnetProbeMu.Lock()
	defer tailnetProbeMu.Unlock()
	return magicsock.KlethronProbePermissionRecovery(mode)
}
