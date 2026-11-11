//go:build klethron_test

package main

import "time"

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
