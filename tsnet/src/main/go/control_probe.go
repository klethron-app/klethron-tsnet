//go:build klethron_test

package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
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
// pending registration cannot be authorized. It returns the SHA-256 hex of the verified chain's
// root certificate, or a fixed diagnostic.
func probeControl(dir string) string {
	if problem := checkProbeEnvironment(); problem != "" {
		return problem
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	controlURL, _ := url.Parse(ipn.DefaultControlURL) // A valid constant.

	// Plain crypto/tls records the verified chain; tlsdial verifies in a callback and records none.
	// Resolution uses bionic via cgo.
	usePlatformRoots()
	dialer := new(tls.Dialer) // Verifies the address's host name.
	address := net.JoinHostPort(controlURL.Host, "443")
	conn, err := dialer.DialContext(ctx, "tcp", address)
	for ; err != nil && ctx.Err() == nil; conn, err = dialer.DialContext(ctx, "tcp", address) {
		time.Sleep(time.Second) // A freshly booted emulator can lack DNS or a route at first.
	}
	if err != nil {
		return "platform tls dial failed"
	}
	chain := conn.(*tls.Conn).ConnectionState().VerifiedChains[0]
	sum := sha256.Sum256(chain[len(chain)-1].Raw)
	_ = conn.Close()

	server := newServer()
	// Explicit, ignoring any TS_CONTROL_URL.
	server.Dir, server.Hostname, server.ControlURL = dir, "klethron-control-test", ipn.DefaultControlURL
	server.Store, server.Ephemeral = new(mem.Store), true
	defer server.Close()
	client, err := server.LocalClient() // Starts: fetches the control key and requests interactive login.
	if err != nil {
		return "start failed"
	}
	watcher, err := client.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		return "watch failed"
	}
	defer watcher.Close()
	var notify ipn.Notify
	for notify.BrowseToURL == nil || *notify.BrowseToURL == "" {
		if notify, err = watcher.Next(); err != nil {
			return "no login url"
		}
	}
	login, err := url.Parse(*notify.BrowseToURL)
	if err != nil || login.Scheme != "https" || login.Host != "login.tailscale.com" || !strings.HasPrefix(login.Path, "/a/") {
		return "unexpected login url"
	}
	status, err := client.StatusWithoutPeers(ctx)
	if err != nil {
		return "status failed"
	}
	if status.BackendState != ipn.NeedsLogin.String() || len(status.TailscaleIPs) != 0 {
		return "unexpected node state"
	}
	return hex.EncodeToString(sum[:])
}
