//go:build klethron_test

package main

import (
	"io"
	"net/http"
	"strings"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/store/mem"
)

type authProbeTransport struct {
	entered chan struct{}
	release chan struct{}
	calls   chan string
}

func (t authProbeTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	t.calls <- strings.TrimPrefix(request.URL.Path, "/localapi/v0/")
	status := http.StatusNoContent
	if request.URL.Path == "/localapi/v0/prefs" {
		close(t.entered)
		select {
		case <-t.release:
		case <-request.Context().Done():
			return nil, request.Context().Err()
		}
		status = http.StatusOK
	}
	return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("{}")), Header: make(http.Header)}, nil
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
	transport := authProbeTransport{make(chan struct{}), make(chan struct{}), make(chan string, 16)}
	n.client = &local.Client{Transport: transport}
	login := make(chan int, 1)
	go func() { login <- n.login() }()
	select {
	case <-transport.entered:
	case <-time.After(5 * time.Second):
		return "login did not start"
	}
	if n.logout(50*time.Millisecond) != resultTimeout {
		return "logout interleaved with login"
	}
	logout := make(chan int, 1)
	go func() { logout <- n.logout(5 * time.Second) }()
	expected := 0
	if mode == "cancel" {
		n.cancel()
		expected = resultClosed
	} else {
		close(transport.release)
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
	if mode != "cancel" {
		want = append(want, "start", "login-interactive")
	}
	for _, path := range want {
		select {
		case got := <-transport.calls:
			if got != path {
				return "authentication calls reordered"
			}
		default:
			return "authentication call missing"
		}
	}
	if len(transport.calls) != 0 {
		return "cancelled authentication reached the backend"
	}
	return "ok"
}
