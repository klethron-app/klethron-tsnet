package main

import (
	"context"
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// node serializes upstream Start and Close, which must not run concurrently, and publishes the
// latest backend status to waiting callers. Start does not wait for the network, so Close waits
// at most for local initialization.
type node struct {
	server *tsnet.Server

	lifecycle       sync.Mutex
	started, closed bool
	stop            func() // Stops status watching and default-network reports once started.
	done            chan struct{}

	mu      sync.Mutex
	status  nodeStatus
	version uint64
	changed chan struct{} // Closed and replaced on every status change.
}

type nodeStatus struct {
	state    ipn.State
	loginURL string
	ip4, ip6 netip.Addr
}

func newNode(server *tsnet.Server) *node {
	return &node{server: server, done: make(chan struct{}), changed: make(chan struct{})}
}

// start configures every connection-relevant field explicitly, so the host process environment
// cannot redirect control. config is "directory\x00hostname\x00controlURL"; an empty control URL
// selects Tailscale's. State persists in a file store in the caller's directory.
func (n *node) start(config string) int {
	n.lifecycle.Lock()
	defer n.lifecycle.Unlock()
	if n.closed {
		return startClosed
	}
	if n.started {
		return startRepeated
	}
	fields := strings.Split(config, "\x00")
	if len(fields) != 3 || !utf8.ValidString(config) || !filepath.IsAbs(fields[0]) || fields[1] == "" {
		return startInvalid
	}
	// tsnet reads an auth key from the environment when none is configured. This library only
	// supports interactive login, so refuse rather than silently use a host-process credential.
	if os.Getenv("TS_AUTHKEY") != "" || os.Getenv("TS_AUTH_KEY") != "" {
		return startAuthEnvironment
	}
	n.started = true // A failed upstream Start cannot be retried.
	n.server.Dir, n.server.Hostname, n.server.ControlURL = fields[0], fields[1], fields[2]
	if n.server.ControlURL == "" {
		n.server.ControlURL = ipn.DefaultControlURL
	}
	if err := n.server.Start(); err != nil {
		return startFailed
	}
	client, err := n.server.LocalClient()
	if err != nil {
		return startFailed
	}
	ctx, cancel := context.WithCancel(context.Background())
	watching := make(chan struct{})
	go func() {
		defer close(watching)
		n.watchStatus(ctx, client)
	}()
	unwatch := watchDefaultNetwork(n.server)
	n.stop = func() {
		unwatch()
		cancel()
		<-watching
	}
	return startOK
}

func (n *node) close() {
	n.lifecycle.Lock()
	defer n.lifecycle.Unlock()
	n.closed = true
	close(n.done)
	if n.stop != nil {
		n.stop()
	}
	_ = n.server.Close()
}

// watchStatus follows the IPN bus until ctx ends. A new watch starts with the current state, so
// re-watching after an unexpected disconnect (such as falling behind) loses nothing needed.
func (n *node) watchStatus(ctx context.Context, client *local.Client) {
	for ctx.Err() == nil {
		if watcher, err := client.WatchIPNBus(ctx, ipn.NotifyInitialState); err == nil {
			for {
				notify, err := watcher.Next()
				if err != nil {
					break
				}
				n.publish(notify)
			}
			watcher.Close()
		}
		select {
		case <-ctx.Done():
		case <-time.After(time.Second):
		}
	}
}

func (n *node) publish(notify ipn.Notify) {
	ip4, ip6 := n.server.TailscaleIPs()
	n.mu.Lock()
	defer n.mu.Unlock()
	status := n.status
	status.ip4, status.ip6 = ip4, ip6
	if notify.State != nil {
		status.state = *notify.State
		if status.state != ipn.NoState && status.state != ipn.NeedsLogin {
			status.loginURL = "" // Used or no longer needed.
		}
	}
	if notify.BrowseToURL != nil {
		status.loginURL = loginURL(*notify.BrowseToURL)
	}
	if status != n.status {
		n.status = status
		n.version++
		close(n.changed)
		n.changed = make(chan struct{})
	}
}

// loginURL returns raw if it is a printable ASCII absolute HTTP(S) URL, otherwise "".
func loginURL(raw string) string {
	for i := 0; i < len(raw); i++ {
		if raw[i] <= ' ' || raw[i] >= 0x7f {
			return ""
		}
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return ""
	}
	return raw
}

// await returns the status record once its version differs from after, or the timeout elapses.
// It returns false once the node is closed.
func (n *node) await(after uint64, timeout time.Duration) (string, bool) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		n.mu.Lock()
		status, version, changed := n.status, n.version, n.changed
		n.mu.Unlock()
		select {
		case <-n.done:
			return "", false
		default:
		}
		if version != after {
			return status.record(version), true
		}
		select {
		case <-changed:
		case <-n.done:
			return "", false
		case <-timer.C:
			return status.record(version), true
		}
	}
}

// Java enum names, in TsnetStatus.State. InUseOtherUser is Windows-only.
var javaStates = map[ipn.State]string{
	ipn.NeedsLogin:       "NEEDS_LOGIN",
	ipn.NeedsMachineAuth: "NEEDS_MACHINE_AUTH",
	ipn.Stopped:          "STOPPED",
	ipn.Starting:         "STARTING",
	ipn.Running:          "RUNNING",
}

// record is "version\nSTATE\nloginURL\naddresses", with comma-separated address literals. All ASCII.
func (s nodeStatus) record(version uint64) string {
	state, ok := javaStates[s.state]
	if !ok {
		state = "NO_STATE"
	}
	var addresses []string
	for _, ip := range []netip.Addr{s.ip4, s.ip6} {
		if ip.IsValid() {
			addresses = append(addresses, ip.String())
		}
	}
	return fmt.Sprintf("%d\n%s\n%s\n%s", version, state, s.loginURL, strings.Join(addresses, ","))
}
