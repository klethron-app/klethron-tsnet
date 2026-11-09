//go:build klethron_test

package main

import "C"

import "time"

//export klethron_concurrent_close_probe
func klethron_concurrent_close_probe() C.int {
	id := klethron_node_create()
	n := lookupNode(id)
	entered, release := make(chan struct{}), make(chan struct{})
	n.stop = func() {
		close(entered)
		<-release
	}
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
	var waited C.int
	select {
	case <-second:
	case <-time.After(100 * time.Millisecond):
		waited = 1
	}
	close(release)
	<-first
	<-second
	return waited
}
