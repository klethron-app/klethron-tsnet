package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"io"
	"log"
	"math"
	"runtime/debug"
	"sync"
	"time"

	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

func init() {
	// Some upstream paths use Go's standard logger directly, for example when the host reports
	// a network change. This library never logs; discard them rather than writing to stderr.
	log.SetOutput(io.Discard)
}

func newServer() *tsnet.Server {
	usePlatformRoots()
	return &tsnet.Server{UserLogf: logger.Discard, Logf: logger.Discard}
}

// Java holds node IDs, never Go pointers. IDs are not reused, so a call racing or following
// close finds no node instead of a different one.
var nodes struct {
	sync.Mutex
	byID map[int64]*node
	last int64
}

func lookupNode(id C.longlong) *node {
	nodes.Lock()
	defer nodes.Unlock()
	return nodes.byID[int64(id)]
}

//export klethron_node_create
func klethron_node_create() C.longlong {
	n := newNode(newServer())
	nodes.Lock()
	defer nodes.Unlock()
	if nodes.byID == nil {
		nodes.byID = make(map[int64]*node)
	}
	nodes.last++
	nodes.byID[nodes.last] = n
	return C.longlong(nodes.last)
}

// Results of klethron_node_start; TsnetNode.start maps them to exceptions.
const (
	startOK = iota
	startClosed
	startRepeated
	startInvalid
	startAuthEnvironment
	startFailed
)

//export klethron_node_start
func klethron_node_start(id C.longlong, config *C.char, length C.int) C.int {
	n := lookupNode(id)
	if n == nil {
		return startClosed
	}
	return C.int(n.start(C.GoStringN(config, length)))
}

// klethron_node_await_status returns the status record, or NULL once the node is closed.
//
//export klethron_node_await_status
func klethron_node_await_status(id C.longlong, after C.longlong, timeoutMillis C.longlong) *C.char {
	n := lookupNode(id)
	if n == nil {
		return nil
	}
	record, open := n.await(uint64(after), milliseconds(timeoutMillis))
	if !open {
		return nil
	}
	return C.CString(record)
}

// klethron_node_dial returns a connected socket descriptor owned by the caller, or a negative
// dial result.
//
//export klethron_node_dial
func klethron_node_dial(id C.longlong, host *C.char, length C.int, port C.int, timeoutMillis C.longlong) C.int {
	n := lookupNode(id)
	if n == nil {
		return dialClosed
	}
	if port < 0 || port > math.MaxUint16 {
		return dialInvalid
	}
	return C.int(n.dial(C.GoStringN(host, length), uint16(port), milliseconds(timeoutMillis)))
}

// milliseconds converts a non-negative Java timeout, saturating rather than overflowing.
func milliseconds(millis C.longlong) time.Duration {
	if millis >= C.longlong(math.MaxInt64/time.Millisecond) {
		return math.MaxInt64
	}
	return time.Duration(millis) * time.Millisecond
}

//export klethron_node_close
func klethron_node_close(id C.longlong) {
	nodes.Lock()
	n := nodes.byID[int64(id)]
	delete(nodes.byID, int64(id))
	nodes.Unlock()
	if n != nil {
		n.close()
	}
}

//export klethron_tailscale_version
func klethron_tailscale_version() *C.char {
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, dependency := range info.Deps {
			if dependency.Path == "tailscale.com" {
				return C.CString(dependency.Version)
			}
		}
	}
	return C.CString("unknown")
}

func main() {}
