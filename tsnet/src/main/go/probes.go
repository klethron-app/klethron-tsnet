//go:build klethron_test

package main

import "C"

import (
	"context"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// Debug-only test bridges, which instrumentation tests call by name through Probe.call with one
// UTF-8 argument. They return fixed ASCII diagnostics, counts, addresses, URLs of loopback test
// servers or public certificate hashes; never credentials.
var probes = map[string]func(string) string{
	"addPeers":              withControl(addPeers),
	"approveMachines":       withControl(approveMachines),
	"authentication":        probeAuthentication,
	"awaitReceived":         awaitReceived,
	"complete":              withControl(completeLogin),
	"control":               probeControl,
	"controlRotation":       probeNonInteractiveRotation,
	"crash":                 probeCrash,
	"defaultRoute":          probeDefaultRoute,
	"dnsRoots":              probeDNSRoots,
	"expire":                withControl(expireNodes),
	"flooded":               flooded,
	"hostnames":             withControl(hostnames),
	"interfaces":            probeInterfaces,
	"loginURL":              probeLoginURL,
	"netchangeAwait":        awaitNetworkChange,
	"netchangeReset":        resetNetworkRebinds,
	"netchangeStart":        startNetworkProbe,
	"netchangeStop":         stopNetworkProbe,
	"netchangeUnchanged":    unchangedNetworkRebinds,
	"rejectRegistrations":   rejectRegistrations,
	"release":               releaseStalled,
	"remove":                removeNodes,
	"rename":                withControl(renameNodes),
	"requireMachineAuth":    withControl(requireMachineAuth),
	"roots":                 probeRoots,
	"setAuthKeyEnvironment": setAuthKeyEnvironment,
	"setPeerOnline":         withControl(setPeerOnline),
	"startControl":          startControl,
	"startPeer":             startPeer,
	"stopControl":           stopControl,
	"store":                 probeStore,
	"tailnet":               func(string) string { return probeTailnet(false) },
	"tailnetDerp":           func(string) string { return probeTailnet(true) },
	"tailnetLock":           probeTailnetLock,
	"tailnetLockRestart":    func(string) string { lockExpectSaved.Store(true); return "ok" },
	"tailnetStore":          probeTailnetStore,
	"watchDisconnect":       probeWatchDisconnect,
	"dialDeadline": func(handle string) string {
		id, _ := strconv.ParseInt(handle, 10, 64)
		dialer := lookupNode(C.longlong(id)).server.Sys().Dialer.Get()
		dial := dialer.NetstackDialTCP
		dialer.NetstackDialTCP = func(ctx context.Context, target netip.AddrPort) (net.Conn, error) {
			conn, err := dial(ctx, target)
			if err == nil {
				<-ctx.Done() // A successful connection can race its deadline.
			}
			return conn, err
		}
		return "ok"
	},
}

// klethron_probe calls the probe named before the first NUL in call with the rest as argument.
//
//export klethron_probe
func klethron_probe(call *C.char, length C.int) *C.char {
	name, argument, _ := strings.Cut(C.GoStringN(call, length), "\x00")
	probe, ok := probes[name]
	if !ok {
		return C.CString("unknown probe")
	}
	return C.CString(probe(argument))
}
