//go:build klethron_test

package main

import "C"

import (
	"strings"
)

// Debug-only test bridges, which instrumentation tests call by name through Probe.call with one
// UTF-8 argument. They return fixed ASCII diagnostics, counts, addresses, URLs of loopback test
// servers or public certificate hashes; never credentials.
var probes = map[string]func(string) string{
	"accessLifetime":        probeAccessLifetime,
	"addPeers":              withControl(addPeers),
	"approveMachines":       withControl(approveMachines),
	"authentication":        probeAuthentication,
	"awaitReceived":         awaitReceived,
	"complete":              withControl(completeLogin),
	"control":               probeControl,
	"controlRotation":       probeNonInteractiveRotation,
	"crash":                 probeCrash,
	"defaultRoute":          probeDefaultRoute,
	"expire":                withControl(expireNodes),
	"flooded":               flooded,
	"hostnames":             withControl(hostnames),
	"interfaces":            probeInterfaces,
	"loginURL":              probeLoginURL,
	"netchangeAwait":        awaitNetworkChange,
	"netchangeStart":        startNetworkProbe,
	"netchangeStop":         stopNetworkProbe,
	"rejectRegistrations":   withControl(rejectRegistrations),
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
