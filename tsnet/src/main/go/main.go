package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"runtime/cgo"
	"runtime/debug"

	"tailscale.com/tsnet"
)

// Handles keep Go pointers out of Java and C. Java serializes access and closes each handle once.
//export klethron_node_create
func klethron_node_create() C.longlong {
	return C.longlong(cgo.NewHandle(new(tsnet.Server)))
}

//export klethron_node_close
func klethron_node_close(id C.longlong) {
	handle := cgo.Handle(id)
	defer handle.Delete()
	// An unstarted server's first Close cannot fail. Revisit when Start is exposed.
	_ = handle.Value().(*tsnet.Server).Close()
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
