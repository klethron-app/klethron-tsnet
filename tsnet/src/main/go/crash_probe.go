//go:build klethron_test

package main

import "sync"

var crashTarget *int

// probeCrash fails fatally on a new goroutine, as an upstream bug would: mode "0" panics, "1"
// dereferences nil and others are a runtime fatal error.
func probeCrash(mode string) string {
	go func() {
		switch mode {
		case "0":
			panic("klethron crash probe")
		case "1":
			*crashTarget = 1
		default:
			var mutex sync.Mutex
			mutex.Unlock()
		}
	}()
	return ""
}
