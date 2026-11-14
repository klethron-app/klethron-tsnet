//go:build klethron_test

package main

import "tailscale.com/net/portmapper"

func probePortmapperRoots(string) string { return portmapper.KlethronProbeRoots() }
