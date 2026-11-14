//go:build klethron_test

package main

// void klethron_timing_event(long long node, long long span, int phase, int event);
import "C"

import (
	"strings"
	"sync"
	"sync/atomic"

	"tailscale.com/ipn"
	"tailscale.com/util/klethrontiming"
)

var timingSequence atomic.Uint64
var timingNodes sync.Map // *node -> uint64, removed after shutdown.

var timingPhases = []string{
	"native_start", "native_close", "control_login", "control_keys", "control_registration",
	"control_connect", "control_http", "control_https", "control_map_request", "control_map_received",
	"netcheck", "stun_wait", "portmap_wait", "relay_connect", "relay_hint", "relay_selected", "rebind",
	"native_dial", "running_wait", "peer_lookup", "peer_tcp", "state_initializing", "state_starting",
	"state_running", "state_stopped", "state_login", "state_approval", "store_read", "store_write",
}

func configureTiming(n *node) {
	id := timingSequence.Add(1)
	timingNodes.Store(n, id)
	n.server.Logf = func(format string, args ...any) {
		// WithPrefix adds static prefixes. Never format the string or retain other log arguments.
		if !strings.HasSuffix(format, klethrontiming.Marker) || len(args) != 3 {
			return
		}
		phase, ok := args[0].(string)
		if !ok {
			return
		}
		event, ok := args[1].(int)
		if !ok || event < 0 || event > 2 {
			return
		}
		span, ok := args[2].(uint64)
		if !ok {
			return
		}
		for index, allowed := range timingPhases {
			if phase == allowed {
				C.klethron_timing_event(C.longlong(id), C.longlong(span), C.int(index), C.int(event))
				return
			}
		}
	}
}

func timingSpan(n *node, phase string) func() { return klethrontiming.Span(n.server.Logf, phase) }

func timingDial(n *node, fd int) func() {
	span, done := klethrontiming.Start(n.server.Logf, "native_dial")
	C.klethron_timing_event(C.longlong(span), C.longlong(fd), 0, 4)
	return done
}

func timingLink(n *node, handle int64) {
	if id, ok := timingNodes.Load(n); ok {
		C.klethron_timing_event(C.longlong(id.(uint64)), C.longlong(handle), 0, 3)
	}
}

func timingState(n *node, state ipn.State) {
	phase := "state_initializing"
	switch state {
	case ipn.Starting:
		phase = "state_starting"
	case ipn.Running:
		phase = "state_running"
	case ipn.Stopped:
		phase = "state_stopped"
	case ipn.NeedsLogin:
		phase = "state_login"
	case ipn.NeedsMachineAuth:
		phase = "state_approval"
	}
	klethrontiming.Mark(n.server.Logf, phase)
}
