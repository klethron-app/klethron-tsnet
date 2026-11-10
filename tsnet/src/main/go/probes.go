//go:build klethron_test

package main

import "C"

import "strings"

// Debug-only test bridges, which instrumentation tests call by name through Probe.call with one
// UTF-8 argument. They return fixed ASCII diagnostics, counts, addresses, URLs of loopback test
// servers or public certificate hashes; never credentials.
var probes = map[string]func(string) string{
	"addPeers":              addPeers,
	"approveMachines":       approveMachines,
	"awaitReceived":         awaitReceived,
	"complete":              completeLogin,
	"concurrentClose":       probeConcurrentClose,
	"control":               probeControl,
	"crash":                 probeCrash,
	"expire":                expireNodes,
	"flooded":               flooded,
	"hostnames":             hostnames,
	"interfaces":            probeInterfaces,
	"netchangeAwait":        awaitNetworkChange,
	"netchangeStart":        startNetworkProbe,
	"netchangeStop":         stopNetworkProbe,
	"rejectRegistrations":   rejectRegistrations,
	"release":               releaseStalled,
	"remove":                removeNodes,
	"requireMachineAuth":    requireMachineAuth,
	"roots":                 probeRoots,
	"setAuthKeyEnvironment": setAuthKeyEnvironment,
	"setPeerOnline":         setPeerOnline,
	"startControl":          startControl,
	"startPeer":             startPeer,
	"stopControl":           stopControl,
	"tailnet":               func(dir string) string { return probeTailnet(dir, false) },
	"tailnetDerp":           func(dir string) string { return probeTailnet(dir, true) },
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
