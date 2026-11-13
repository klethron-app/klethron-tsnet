//go:build klethron_test

package main

import (
	"context"

	"tailscale.com/ipn"
)

// probeAccessLifetime replaces access after wait observes Running but before it returns.
// A dial must keep the cancelled lifetime it observed, even if a new login already runs.
func probeAccessLifetime(string) string {
	n := newNode(nil)
	defer n.cancel()
	n.status.state = ipn.Running
	_, _, access := n.wait(n.ctx, func(status nodeStatus, _ uint64) bool {
		n.mu.Lock()
		n.endAccess()
		n.access, n.endAccess = context.WithCancel(n.ctx)
		n.mu.Unlock()
		return status.state == ipn.Running
	})
	if access.Err() == nil {
		return "wait adopted the next login's access"
	}
	return "ok"
}
