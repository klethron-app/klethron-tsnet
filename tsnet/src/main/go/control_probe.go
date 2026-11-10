//go:build klethron_test

package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/url"
	"strings"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/store/mem"
)

// probeControl contacts Tailscale's real control plane without credentials. It resolves and
// verifies the server through the platform, then starts an ephemeral node until control offers an
// interactive login URL. The URL is checked here and never returned, logged or opened, so the
// pending registration cannot be authorized. Results are fixed diagnostics and a public root hash.
func probeControl(dir string) string {
	result := struct{ Result, Root string }{}
	result.Root, result.Result = controlRoot(dir)
	data, _ := json.Marshal(result) // Strings only; cannot fail.
	return string(data)
}

func controlRoot(dir string) (root, problem string) {
	if problem := checkProbeEnvironment(dir); problem != "" {
		return "", problem
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	controlURL, _ := url.Parse(ipn.DefaultControlURL) // A valid constant.

	// Plain crypto/tls records the verified chain; tlsdial verifies in a callback and records none.
	// Resolution uses bionic via cgo.
	usePlatformRoots()
	dialer := &tls.Dialer{NetDialer: new(net.Dialer), Config: &tls.Config{ServerName: controlURL.Host}}
	conn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(controlURL.Host, "443"))
	if err != nil {
		return "", "platform tls dial failed"
	}
	chain := conn.(*tls.Conn).ConnectionState().VerifiedChains[0]
	sum := sha256.Sum256(chain[len(chain)-1].Raw)
	_ = conn.Close()

	server := newServer()
	server.Dir = dir
	server.Hostname = "klethron-control-test"
	server.ControlURL = controlURL.String() // Explicit, ignoring any TS_CONTROL_URL.
	server.Store = new(mem.Store)
	server.Ephemeral = true
	err = server.Start() // Fetches the control key and requests interactive login.
	defer server.Close()
	if err != nil {
		return "", "start failed"
	}
	client, err := server.LocalClient()
	if err != nil {
		return "", "local client failed"
	}
	watcher, err := client.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		return "", "watch failed"
	}
	defer watcher.Close()
	for {
		notify, err := watcher.Next()
		if err != nil {
			return "", "no login url"
		}
		if notify.BrowseToURL == nil || *notify.BrowseToURL == "" {
			continue
		}
		login, err := url.Parse(*notify.BrowseToURL)
		if err != nil || login.Scheme != "https" || login.Host != "login.tailscale.com" || !strings.HasPrefix(login.Path, "/a/") {
			return "", "unexpected login url"
		}
		break
	}
	status, err := client.StatusWithoutPeers(ctx)
	if err != nil {
		return "", "status failed"
	}
	if status.BackendState != ipn.NeedsLogin.String() || len(status.TailscaleIPs) != 0 {
		return "", "unexpected node state"
	}
	return hex.EncodeToString(sum[:]), "ok"
}
