//go:build klethron_test

package main

import "C"

import (
	"strconv"
	"strings"
)

// Debug-only test bridges, which instrumentation tests call by name through Probe.call with one
// UTF-8 argument. They return fixed ASCII diagnostics, counts, addresses, URLs of loopback test
// servers or public certificate hashes; never credentials.
var probes = map[string]func(string) string{
	"accessLifetime":        probeAccessLifetime,
	"addPeers":              withControl(addPeers),
	"approveMachines":       withControl(approveMachines),
	"awaitReceived":         awaitReceived,
	"complete":              withControl(completeLogin),
	"control":               probeControl,
	"crash":                 probeCrash,
	"defaultRoute":          probeDefaultRoute,
	"expire":                withControl(expireNodes),
	"flooded":               flooded,
	"hostnames":             withControl(hostnames),
	"interfaces":            probeInterfaces,
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
	"tailnet":               func(dir string) string { return probeTailnet(dir, false) },
	"tailnetDerp":           func(dir string) string { return probeTailnet(dir, true) },
	"holdDirectory": func(dir string) string {
		return strconv.FormatInt(int64(startNode(dir+"\x00crash\x00http://127.0.0.1:1")), 10)
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
