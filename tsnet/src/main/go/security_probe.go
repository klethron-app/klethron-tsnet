//go:build klethron_test

package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func probeLoginURL(argument string) string {
	control, raw, _ := strings.Cut(argument, "\x00")
	n := newNode(newServer())
	defer n.cancel()
	n.server.ControlURL = control
	if n.loginURL(raw) == "" {
		return "rejected"
	}
	return "accepted"
}

// A watch gap or a changed node key must end the old access even if the next state is Running.
func probeWatchDisconnect(reason string) string {
	n := newNode(newServer())
	n.server.Hostname, n.server.ControlURL = "watch-test", "http://127.0.0.1:1"
	n.server.Store, n.server.Ephemeral = new(mem.Store), true
	defer n.close()
	if err := n.server.Start(); err != nil {
		return "start failed"
	}
	body, writer := io.Pipe()
	defer body.Close()
	defer writer.Close()
	n.client = &local.Client{Transport: probeTransport(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: body, Header: make(http.Header)}, nil
	})}
	n.watchers.Go(func() { n.watchStatus(make(chan struct{}, 1)) })
	ctx, cancel := context.WithTimeout(n.ctx, 5*time.Second)
	defer cancel()
	unblock := context.AfterFunc(ctx, func() { _ = writer.Close() })
	defer unblock()
	encoder := json.NewEncoder(writer)
	self := &tailcfg.Node{Key: key.NewNode().Public()}
	url := n.server.ControlURL + "/auth/test"
	if err := encoder.Encode(ipn.Notify{State: new(ipn.Running),
		InitialStatus: &ipnstate.Status{AuthURL: url, Self: &ipnstate.PeerStatus{PublicKey: self.Key}}}); err != nil {
		return "initial notification failed"
	}
	status, _, access := n.wait(ctx, func(s nodeStatus, _ uint64) bool { return s.state == ipn.Running })
	if status.loginURL != url || access.Err() != nil || ctx.Err() != nil {
		return "initial renewal or access unavailable"
	}
	// An empty AuthURL can precede persistence; keep the renewal until the key changes.
	renewal := url + "/renew"
	for _, test := range []struct{ raw, want string }{{renewal, renewal}, {"", renewal}, {"http://unexpected.example/auth", ""}} {
		n.publish(ipn.Notify{InitialStatus: &ipnstate.Status{AuthURL: test.raw}}, nil)
		status, _, _ := n.wait(ctx, func(nodeStatus, uint64) bool { return true })
		if status.loginURL != test.want {
			return "watch snapshot lost a renewal or accepted an unsafe login URL"
		}
	}
	if reason == "key" {
		n.publish(ipn.Notify{SelfChange: self}, nil)
		if access.Err() != nil {
			return "unchanged key ended access"
		}
		// Rotate after wait takes its snapshot; it must return the old access lifetime.
		_, _, access = n.wait(ctx, func(nodeStatus, uint64) bool {
			n.publish(ipn.Notify{SelfChange: &tailcfg.Node{Key: key.NewNode().Public()}}, nil)
			return true
		})
	} else if reason == "lag" {
		if err := encoder.Encode(ipn.Notify{ErrMessage: new(watchFellBehind)}); err != nil {
			return "lag notification failed"
		}
	}
	if reason == "eof" {
		_ = writer.Close()
	}
	select {
	case <-access.Done():
	case <-ctx.Done():
		return "previous access remained live"
	}
	// Simulate the reconnect snapshot before callers using the old context have returned.
	if reason != "key" {
		n.publish(ipn.Notify{State: new(ipn.Running)}, nil)
	}
	n.mu.Lock()
	renewed := n.access
	n.mu.Unlock()
	if renewed == access || renewed.Err() != nil || access.Err() == nil {
		return "new login reused old access"
	}
	return "ok"
}
