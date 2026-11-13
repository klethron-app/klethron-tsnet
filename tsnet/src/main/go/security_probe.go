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
	"tailscale.com/ipn/store/mem"
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

type watchProbeTransport struct{ body io.ReadCloser }

func (t watchProbeTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return &http.Response{StatusCode: http.StatusOK, Body: t.body, Header: make(http.Header)}, nil
}

// Drive the real watch loop through EOF and a lag notification, without needing to flood
// the backend. A missed logout/login must cancel the old access even if the next state is Running.
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
	n.client = &local.Client{Transport: watchProbeTransport{body}}
	n.watchers.Go(func() { n.watchStatus(make(chan struct{}, 1)) })
	ctx, cancel := context.WithTimeout(n.ctx, 5*time.Second)
	defer cancel()
	unblock := context.AfterFunc(ctx, func() { _ = writer.Close() })
	defer unblock()
	encoder := json.NewEncoder(writer)
	if err := encoder.Encode(ipn.Notify{State: new(ipn.Running)}); err != nil {
		return "initial notification failed"
	}
	_, _, access := n.wait(ctx, func(status nodeStatus, _ uint64) bool { return status.state == ipn.Running })
	if access.Err() != nil || ctx.Err() != nil {
		return "initial access unavailable"
	}
	if reason == "lag" {
		if err := encoder.Encode(ipn.Notify{ErrMessage: new(watchFellBehind)}); err != nil {
			return "lag notification failed"
		}
	}
	_ = writer.Close()
	select {
	case <-access.Done():
	case <-ctx.Done():
		return "watch gap retained access"
	}
	// Simulate the reconnect snapshot before callers using the old context have returned.
	n.publish(ipn.Notify{State: new(ipn.Running)}, nil)
	n.mu.Lock()
	renewed := n.access
	n.mu.Unlock()
	if renewed == access || renewed.Err() != nil || access.Err() == nil {
		return "reconnect reused old access"
	}
	return "ok"
}
