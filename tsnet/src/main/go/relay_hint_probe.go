//go:build klethron_test

package main

import (
	"context"
	"strings"
	"sync"
	"time"

	"tailscale.com/health"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
	"tailscale.com/util/eventbus"
	"tailscale.com/util/usermetric"
	"tailscale.com/wgengine/magicsock"
)

// Probe the real selection helper with no private key, so it cannot contact a relay.
func probeRelayHint(mode string) string {
	bus := eventbus.New()
	defer bus.Close()
	monitor := netmon.NewStatic()
	defer monitor.Close()
	connection, err := magicsock.NewConn(magicsock.Options{EventBus: bus, Logf: logger.Discard,
		NetMon: monitor, HealthTracker: health.NewTracker(bus), Metrics: new(usermetric.Registry), DisablePortMapper: true})
	if err != nil {
		return "connection failed"
	}
	defer connection.Close()
	client := bus.Client("relay-hint-test")
	defer client.Close()
	events := eventbus.Subscribe[magicsock.HomeDERPChanged](client)
	defer events.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	expect := func(old, next tailcfg.DERPRegionID) bool {
		select {
		case event := <-events.Events():
			return event.Old == old && event.New == next
		case <-ctx.Done():
			return false
		}
	}
	home := func() string {
		var status ipnstate.StatusBuilder
		connection.UpdateStatus(&status)
		return status.Status().Self.Relay
	}
	connection.SeedHomeDERP(1) // No current DERP map.
	regions := make(map[tailcfg.DERPRegionID]*tailcfg.DERPRegion)
	for id := tailcfg.DERPRegionID(1); id <= 6; id++ {
		regions[id] = &tailcfg.DERPRegion{RegionID: id, RegionCode: string(rune('a' + id)),
			Nodes: []*tailcfg.DERPNode{{RegionID: id, HostName: "127.0.0.1", IPv4: "127.0.0.1", IPv6: "none"}}}
	}
	regions[3].Nodes = nil
	regions[4].Nodes[0].STUNOnly = true
	//lint:ignore SA1019 Control can still supply the legacy field; reject that hint too.
	regions[5].Avoid = true
	regions[6].NoMeasureNoHome = true
	connection.SetDERPMapWithoutReSTUN(&tailcfg.DERPMap{Regions: regions})
	for _, id := range []tailcfg.DERPRegionID{0, 3, 4, 5, 6, 99} {
		connection.SeedHomeDERP(id)
		if home() != "" {
			return "accepted an ineligible hint"
		}
	}
	if mode == "race" {
		var competing sync.WaitGroup
		competing.Go(func() { connection.SeedHomeDERP(1) })
		competing.Go(func() { connection.ForceSetNearestDERP(2) })
		competing.Wait()
		if home() != "c" {
			return "hint replaced the selected home"
		}
		select {
		case first := <-events.Events():
			if first.Old != 0 || (first.New != 1 && first.New != 2) {
				return "invalid initial home event"
			}
			if first.New == 1 && !expect(1, 2) {
				return "racing home changes reordered"
			}
		case <-ctx.Done():
			return "missing initial home event"
		}
	} else {
		connection.SeedHomeDERP(1)
		if home() != "b" || !expect(0, 1) {
			return "hint did not select and publish the home"
		}
		connection.SeedHomeDERP(2)
		if home() != "b" {
			return "hint replaced an existing home"
		}
		connection.ForceSetNearestDERP(2)
		if !expect(1, 2) {
			return "selection did not replace the hint"
		}
	}
	connection.SeedHomeDERP(1)
	connection.ForceSetNearestDERP(2) // No change, hence no event.
	connection.ForceSetNearestDERP(1)
	if !expect(2, 1) {
		return "ignored or unchanged hint published an event"
	}
	return "ok"
}

// Configure only newly starting test clients; the existing echo peer keeps its real relay.
func setRelayHint(control *testcontrol.Server, mode string) string {
	control.ModifyFirstMapResponse = func(response *tailcfg.MapResponse, _ *tailcfg.MapRequest) {
		if response.Node == nil || !strings.HasPrefix(response.Node.Name, "klethron-startup.") {
			return
		}
		response.Node.HomeDERP = 2
		if mode == "missing" {
			return
		}
		response.DERPMap = response.DERPMap.Clone()
		region := response.DERPMap.Regions[1].Clone()
		region.RegionID, region.RegionCode = 2, "unreachable"
		for _, node := range region.Nodes {
			node.RegionID, node.DERPPort, node.STUNPort = 2, 1, -1
		}
		response.DERPMap.Regions[2] = region
	}
	return "ok"
}

// Ordinary network probing may finish after the node can already reach its peer through another relay.
func awaitRelayHome(argument string) string {
	n, _ := probeDialNode(argument)
	ctx, done, result := n.whenOpen(10 * time.Second)
	if result != 0 {
		return "node closed"
	}
	defer done()
	client := n.server.Sys().Bus.Get().Client("relay-fallback-test")
	defer client.Close()
	events := eventbus.Subscribe[magicsock.HomeDERPChanged](client)
	defer events.Close()
	for n.server.Status().Self.Relay != "loopback" {
		select {
		case <-events.Events():
		case <-ctx.Done():
			return "network probing did not replace the unavailable hint"
		}
	}
	return "ok"
}

// An already-running watch's initial snapshot must be usable without waiting for the metadata worker.
func probeReadyStatus(control string) string {
	n := newNode(newServer())
	n.server.Hostname, n.server.ControlURL = "klethron-startup", control
	n.server.Store, n.server.Ephemeral = new(mem.Store), true
	defer n.close()
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if _, err := n.server.Up(ctx); err != nil {
		return "node up failed"
	}
	var err error
	n.client, err = n.server.LocalClient()
	if err != nil {
		return "local client failed"
	}
	// No refreshStatus goroutine: the watcher must read metadata with the initial Running event.
	n.watchers.Go(func() { n.watchStatus(make(chan struct{}, 1)) })
	status, _, _ := n.wait(ctx, func(status nodeStatus, _ uint64) bool { return status.state == ipn.Running })
	if ctx.Err() != nil || status.dnsName != "klethron-startup.tail-scale.ts.net" ||
		!strings.Contains(status.peers, "\nklethron-peer.tail-scale.ts.net\t") {
		return "Running was published without the fresh peer map"
	}
	return "ok"
}
