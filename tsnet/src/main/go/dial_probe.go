//go:build klethron_test

package main

import "C"

import (
	"context"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration/testcontrol"
)

func probeDialNode(argument string) (*node, string) {
	handle, value, _ := strings.Cut(argument, ",")
	id, _ := strconv.ParseInt(handle, 10, 64)
	return lookupNode(C.longlong(id)), value
}

// Require both address families from the fast map lookup, independent of record order.
func probeDialCandidates(argument string) string {
	n, host := probeDialNode(argument)
	ctx, done, result := n.whenOpen(5 * time.Second)
	if result != 0 {
		return "node closed"
	}
	defer done()
	addresses, err := n.server.LookupIPForDial(ctx, host)
	if err != nil || len(addresses) != 2 || addresses[0].Is4() == addresses[1].Is4() {
		return "missing an address family"
	}
	// The internal single-address path must also choose the requested family from the map.
	for _, network := range []string{"tcp4", "tcp6"} {
		conn, err := n.server.Sys().Dialer.Get().UserDial(ctx, network, net.JoinHostPort(host, "7070"))
		if err != nil {
			return "family-specific map dial failed"
		}
		remote, err := netip.ParseAddrPort(conn.RemoteAddr().String())
		_ = conn.Close()
		if err != nil || remote.Addr().Unmap().Is4() != (network == "tcp4") {
			return "map dial used the wrong address family"
		}
	}
	return "ok"
}

func disableMagicDNS(control *testcontrol.Server, _ string) string {
	control.SetDNSConfig(&tailcfg.DNSConfig{})
	return "ok"
}

type dialRaceState struct{ started, closed atomic.Int32 }

var dialRaceProbe atomic.Pointer[dialRaceState]

type delayedDialConn struct {
	net.Conn
	state *dialRaceState
}

func (c delayedDialConn) Close() error {
	err := c.Conn.Close()
	c.state.closed.Add(1)
	return err
}

// Delay successful connections until their cancellation, reproducing successful losing/late
// attempts. With mode "loser" only IPv6 is delayed; "cancel" delays both families.
func probeDialRace(argument string) string {
	n, mode := probeDialNode(argument)
	state := new(dialRaceState)
	dialRaceProbe.Store(state)
	dialer := n.server.Sys().Dialer.Get()
	dial := dialer.NetstackDialTCP
	dialer.NetstackDialTCP = func(ctx context.Context, address netip.AddrPort) (net.Conn, error) {
		conn, err := dial(ctx, address)
		if err != nil {
			return conn, err
		}
		if mode == "loser" && address.Addr().Is4() {
			// Ensure the other family has produced a connection before allowing a winner.
			for state.started.Load() == 0 && ctx.Err() == nil {
				time.Sleep(time.Millisecond)
			}
			return conn, nil
		}
		state.started.Add(1)
		<-ctx.Done()
		return delayedDialConn{conn, state}, nil
	}
	return "ok"
}

func awaitDialRace(argument string) string {
	stage, count, _ := strings.Cut(argument, ",")
	want, _ := strconv.ParseInt(count, 10, 32)
	state := dialRaceProbe.Load()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		got := state.started.Load()
		if stage == "closed" {
			got = state.closed.Load()
		}
		if got == int32(want) {
			return "ok"
		}
	}
	return "dial attempts did not reach " + stage
}
