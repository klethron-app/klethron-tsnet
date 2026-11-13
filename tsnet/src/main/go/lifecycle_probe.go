//go:build klethron_test

package main

import (
	"context"
	"time"

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

// probeConcurrentClose returns "ok" if a second close waits for a first one still shutting down.
func probeConcurrentClose(string) string {
	n := newNode(newServer())
	id := addNode(n)
	entered, release := make(chan struct{}), make(chan struct{})
	n.watchers.Go(func() {
		<-n.ctx.Done() // The first close has begun.
		close(entered)
		<-release
	})
	first, second := make(chan struct{}), make(chan struct{})
	go func() {
		klethron_node_close(id)
		close(first)
	}()
	<-entered // Hold the first close inside shutdown while the second close starts.
	go func() {
		klethron_node_close(id)
		close(second)
	}()
	result := "ok"
	select {
	case <-second:
		result = "second close did not wait"
	case <-time.After(100 * time.Millisecond):
	}
	close(release)
	<-first
	<-second
	return result
}
