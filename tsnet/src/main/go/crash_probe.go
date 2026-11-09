//go:build klethron_test

package main

import "C"

import "sync"

var crashTarget *int

// klethron_crash_probe fails fatally on a new goroutine, as an upstream bug would: mode 0 panics,
// 1 dereferences nil and 2 is a runtime fatal error.
//
//export klethron_crash_probe
func klethron_crash_probe(mode C.int) {
	go func() {
		switch mode {
		case 0:
			panic("klethron crash probe")
		case 1:
			*crashTarget = 1
		default:
			var mutex sync.Mutex
			mutex.Unlock()
		}
	}()
}
