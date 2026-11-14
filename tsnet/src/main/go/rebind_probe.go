//go:build klethron_test

package main

import "tailscale.com/wgengine/magicsock"

func probePermissionRecovery(mode string) string {
	tailnetProbeMu.Lock()
	defer tailnetProbeMu.Unlock()
	return magicsock.KlethronProbePermissionRecovery(mode)
}
