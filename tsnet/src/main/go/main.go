package main

// #include "store.h"
// #cgo CFLAGS: -Wall -Wextra -Wno-unused-parameter -Werror -fstack-protector-strong
import "C"

import (
	"io"
	"log"
	"runtime/debug"
	"sync"
	"time"

	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

// Upstream also logs through Go's standard logger, as on network changes. Logs can contain login URLs.
func init() { log.SetOutput(io.Discard) }

func newServer() *tsnet.Server {
	return &tsnet.Server{UserLogf: logger.Discard, Logf: logger.Discard}
}

// Java holds node IDs, never Go pointers. IDs are not reused, so a call racing or following
// close finds no node, not a different one; node calls report a missing node as closed.
var nodes = struct {
	sync.Mutex
	byID map[int64]*node
	last int64
}{byID: make(map[int64]*node)}

func lookupNode(id C.longlong) *node {
	nodes.Lock()
	defer nodes.Unlock()
	return nodes.byID[int64(id)]
}

func addNode(n *node) C.longlong {
	nodes.Lock()
	defer nodes.Unlock()
	nodes.last++
	nodes.byID[nodes.last] = n
	return C.longlong(nodes.last)
}

// klethron_node_start starts a node and returns its positive ID, or a negative failure result.
//
//export klethron_node_start
func klethron_node_start(store *C.state_store, config *C.char, length C.int) C.longlong {
	n := newNode(newServer())
	n.store = &javaStore{handle: store, node: n}
	n.server.Store = n.store
	n.lifecycle.RLock() // A failing startup callback must not close the server during Start.
	result := n.start(C.GoStringN(config, length))
	n.lifecycle.RUnlock()
	if result != 0 {
		n.close()
		return C.longlong(result)
	}
	return addNode(n)
}

// klethron_node_await_status returns the changed status, "" if unchanged, or NULL after close.
//
//export klethron_node_await_status
func klethron_node_await_status(id C.longlong, after C.longlong, timeout C.longlong) *C.char {
	if record, open := lookupNode(id).await(uint64(after), time.Duration(timeout)); open {
		return C.CString(record)
	}
	return nil
}

//export klethron_node_dial
func klethron_node_dial(id C.longlong, host *C.char, length C.int, port C.int, socket C.int, timeout C.longlong) C.int {
	return C.int(lookupNode(id).dial(C.GoStringN(host, length), uint16(port), int(socket), time.Duration(timeout)))
}

//export klethron_node_logout
func klethron_node_logout(id C.longlong, timeout C.longlong) C.int {
	return C.int(lookupNode(id).logout(time.Duration(timeout)))
}

//export klethron_node_login
func klethron_node_login(id C.longlong) C.int {
	return C.int(lookupNode(id).login())
}

//export klethron_node_close
func klethron_node_close(id C.longlong) {
	if n := lookupNode(id); n != nil {
		n.close()
		nodes.Lock()
		delete(nodes.byID, int64(id))
		nodes.Unlock()
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
