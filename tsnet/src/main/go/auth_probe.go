//go:build klethron_test

package main

import (
	"io"
	"net/http"
	"slices"
	"strings"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/store/mem"
)

type authProbeTransport func(*http.Request) (*http.Response, error)

func (t authProbeTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	return t(request)
}

// Pause login after reading prefs. Logout must neither change those prefs underneath login
// nor ignore its own deadline while waiting. Shutdown must cancel both calls.
func probeAuthentication(mode string) string {
	n := newNode(newServer())
	n.server.Hostname, n.server.ControlURL = "auth-test", "http://127.0.0.1:1"
	n.server.Store, n.server.Ephemeral = new(mem.Store), true
	defer n.close()
	if err := n.server.Start(); err != nil {
		return "start failed"
	}
	entered, release := make(chan struct{}), make(chan struct{})
	var calls []string // Written by login, read after it returns.
	n.client = &local.Client{Transport: authProbeTransport(func(request *http.Request) (*http.Response, error) {
		path := strings.TrimPrefix(request.URL.Path, "/localapi/v0/")
		calls = append(calls, path)
		status := http.StatusNoContent
		if path == "prefs" {
			close(entered)
			select {
			case <-release:
			case <-request.Context().Done():
				return nil, request.Context().Err()
			}
			status = http.StatusOK
		}
		if mode == path {
			n.cancel() // A successful response can race shutdown or a storage failure.
		}
		return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("{}")), Header: make(http.Header)}, nil
	})}
	login := make(chan int, 1)
	go func() { login <- n.login() }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		return "login did not start"
	}
	if n.logout(50*time.Millisecond) != resultTimeout {
		return "logout interleaved with login"
	}
	logout := make(chan int, 1)
	go func() { logout <- n.logout(5 * time.Second) }()
	expected := resultClosed
	if mode == "finish" {
		expected = 0
	}
	if mode == "cancel" {
		n.cancel()
	} else {
		close(release)
	}
	for _, result := range []chan int{login, logout} {
		select {
		case got := <-result:
			if got != expected {
				return "authentication result mismatch"
			}
		case <-time.After(5 * time.Second):
			return "authentication stuck"
		}
	}
	want := []string{"prefs"}
	if mode == "start" || mode == "finish" {
		want = append(want, "start")
	}
	if mode == "finish" {
		want = append(want, "login-interactive")
	}
	if !slices.Equal(calls, want) {
		return "authentication calls reordered or continued after cancellation"
	}
	return "ok"
}
