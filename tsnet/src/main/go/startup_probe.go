//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"tailscale.com/feature/buildfeatures"
	"tailscale.com/ipn/store/mem"
)

// This debug-only probe exercises real startup without a tailnet or credentials.
// It returns fixed ASCII diagnostics only, never state, upstream errors or URLs.
//
//export klethron_startup_probe
func klethron_startup_probe(path *C.char, length C.int) *C.char {
	return C.CString(probeStartup(C.GoStringN(path, length)))
}

// checkProbeEnvironment returns a fixed diagnostic, or "" if a probe may start nodes in dir.
func checkProbeEnvironment(dir string) string {
	if !utf8.ValidString(dir) || strings.ContainsRune(dir, 0) || !filepath.IsAbs(dir) {
		return "invalid directory"
	}
	if buildfeatures.HasLogTail {
		return "logtail must be disabled"
	}
	// Refuse ambient auth configuration instead of risking an OAuth exchange.
	for _, name := range []string{"TS_AUTHKEY", "TS_AUTH_KEY", "TS_CLIENT_SECRET", "TS_CLIENT_ID", "TS_ID_TOKEN", "TS_AUDIENCE"} {
		if os.Getenv(name) != "" {
			return "unexpected authentication environment"
		}
	}
	return ""
}

func probeStartup(dir string) string {
	if problem := checkProbeEnvironment(dir); problem != "" {
		return problem
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "loopback listen failed"
	}
	requested := make(chan struct{}, 1)
	var unexpected atomic.Bool
	control := &http.Server{
		ReadHeaderTimeout: 5 * time.Second,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet || r.URL.Path != "/key" || r.ContentLength != 0 || r.Header.Get("Authorization") != "" {
				unexpected.Store(true)
			}
			// Never provide a control-server key: registration cannot proceed.
			w.WriteHeader(http.StatusServiceUnavailable)
			select {
			case requested <- struct{}{}:
			default:
			}
		}),
	}
	served := make(chan struct{})
	go func() {
		defer close(served)
		_ = control.Serve(listener)
	}()
	defer func() {
		_ = control.Close()
		<-served
	}()

	server := newServer()
	server.Dir = dir
	server.Hostname = "klethron-startup-test"
	server.ControlURL = "http://" + listener.Addr().String()
	server.Store = new(mem.Store)
	server.Ephemeral = true
	// Start and Close are deliberately sequential. This is not a cancellation API.
	err = server.Start()
	defer server.Close()
	if err != nil {
		return "start failed"
	}
	if err := server.Start(); err != nil {
		return "repeat start failed"
	}
	client, err := server.LocalClient()
	if err != nil {
		return "local client failed"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	status, err := client.StatusWithoutPeers(ctx)
	if err != nil {
		return "status failed"
	}
	if status.BackendState != "NeedsLogin" || len(status.TailscaleIPs) != 0 {
		return "unexpected node state"
	}
	select {
	case <-requested:
	case <-ctx.Done():
		return "no loopback bootstrap request"
	}
	if unexpected.Load() {
		return "unexpected control request"
	}
	return "ok"
}
