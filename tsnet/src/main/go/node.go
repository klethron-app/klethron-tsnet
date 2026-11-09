package main

import (
	"context"
	"fmt"
	"io"
	"math"
	"net"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsconst"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
)

// node serializes upstream Start and Close, which must not run concurrently, publishes the
// latest backend status to waiting callers and dials through the tailnet. Start does not wait
// for the network, so Close waits at most for local initialization.
type node struct {
	server *tsnet.Server

	lifecycle sync.Mutex
	started   bool
	stop      func() // Stops status watching and default-network reports once started successfully.

	ctx    context.Context // Cancelled when the node closes.
	cancel context.CancelFunc

	mu        sync.Mutex
	selfKey   key.NodePublic // Of the latest network map.
	keyExpiry time.Time      // Of the latest network map; zero without one or if the key never expires.
	status    nodeStatus
	version   uint64
	changed   chan struct{} // Closed and replaced on every status change.
}

type nodeStatus struct {
	state      ipn.State
	revocation string // A TsnetStatus.Revocation name while NeedsLogin, or "".
	loginURL   string
	ip4, ip6   netip.Addr
	err        string // The latest backend error since the node last ran, as printable ASCII.
}

func newNode(server *tsnet.Server) *node {
	ctx, cancel := context.WithCancel(context.Background())
	return &node{server: server, ctx: ctx, cancel: cancel, changed: make(chan struct{})}
}

// start configures every connection-relevant field explicitly, so the host process environment
// cannot redirect control. config is "directory\x00hostname\x00controlURL"; an empty control URL
// selects Tailscale's. State persists in a file store in the caller's directory.
func (n *node) start(config string) int {
	n.lifecycle.Lock()
	defer n.lifecycle.Unlock()
	if n.ctx.Err() != nil {
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
	ctx, cancel := context.WithCancel(n.ctx)
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

// close wakes waiters and cancels dials. Closing the server ends relayed connections.
func (n *node) close() {
	n.cancel()
	n.lifecycle.Lock()
	defer n.lifecycle.Unlock()
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
	// Set before upstream reports NeedsLogin for it; health notifications may arrive later.
	_, removed := n.server.Sys().HealthTracker.Get().CurrentState().Warnings[tsconst.HealthWarnableNodeRemoved]
	n.mu.Lock()
	defer n.mu.Unlock()
	status := n.status
	status.ip4, status.ip6 = ip4, ip6
	if notify.State != nil {
		status.state = *notify.State
		switch status.state {
		case ipn.NoState: // A new profile, as after a logout, has no network map yet.
			n.keyExpiry = time.Time{}
		case ipn.Running:
			status.err = ""
		}
	}
	if notify.ErrMessage != nil && *notify.ErrMessage != watchFellBehind {
		status.err = printable(*notify.ErrMessage, 256)
	}
	if notify.BrowseToURL != nil {
		status.loginURL = loginURL(*notify.BrowseToURL)
	}
	// Every interactive login creates a new node key, which upstream saves before announcing the
	// first network map with it. Closing the node at LoginFinished, which comes first, would lose the
	// login. A re-authenticating node stays Running, so the state does not tell either.
	if self := notify.SelfChange; self != nil {
		n.keyExpiry = self.KeyExpiry
		if self.Key != n.selfKey {
			n.selfKey = self.Key
			status.loginURL = ""
		}
	}
	// As upstream's state machine, which reports NeedsLogin for these reasons too.
	status.revocation = ""
	if status.state == ipn.NeedsLogin {
		if removed {
			status.revocation = "NODE_REMOVED"
		} else if !n.keyExpiry.IsZero() && n.keyExpiry.Before(time.Now()) {
			status.revocation = "KEY_EXPIRED"
		}
	}
	if status != n.status {
		n.status = status
		n.version++
		close(n.changed)
		n.changed = make(chan struct{})
	}
}

// watchFellBehind is the final ErrMessage of a watch that upstream closes for falling behind
// (ipnlocal.watchIPNBusFellBehindMessage). It is not a backend error, and watchStatus re-watches.
const watchFellBehind = "IPN bus consumer fell behind; closing watch"

// printable returns at most limit bytes of s, replacing bytes other than printable ASCII with '?'.
func printable(s string, limit int) string {
	b := []byte(s[:min(len(s), limit)])
	for i, c := range b {
		if c < ' ' || c > '~' {
			b[i] = '?'
		}
	}
	return string(b)
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
		if n.ctx.Err() != nil {
			return "", false
		}
		if version != after {
			return status.record(version), true
		}
		select {
		case <-changed:
		case <-n.ctx.Done():
			return "", false
		case <-timer.C:
			return status.record(version), true
		}
	}
}

// Failures of dial and logout, which otherwise return a file descriptor or 0; TsnetNode.check
// maps them to exceptions.
const (
	resultClosed = -1 - iota
	resultNotStarted
	resultInvalid
	resultUnknownHost
	resultNoRoute
	resultTimeout
	resultDialFailed
	resultLogoutFailed
	resultLoginFailed
)

// whenStarted returns a context for a call on a started node, ending at the timeout or when the node
// closes, or a failure result. It waits for a concurrent start.
func (n *node) whenStarted(timeout time.Duration) (context.Context, context.CancelFunc, int) {
	n.lifecycle.Lock()
	started := n.stop != nil
	n.lifecycle.Unlock()
	if n.ctx.Err() != nil {
		return nil, nil, resultClosed
	}
	if !started {
		return nil, nil, resultNotStarted
	}
	ctx, cancel := context.WithTimeout(n.ctx, timeout)
	return ctx, cancel, 0
}

// failed maps a failure of a call with ctx from whenStarted to closed or timed out, if applicable.
func (n *node) failed(ctx context.Context, result int) int {
	if n.ctx.Err() != nil {
		return resultClosed
	}
	if ctx.Err() != nil {
		return resultTimeout
	}
	return result
}

// dial connects to host:port through the tailnet and returns one end of a Unix socket pair that
// relays the connection, owned by the caller, or a dial result. It waits up to timeout for the
// node to run. Unlike upstream Server.Dial, it never connects through the device's own network
// and never starts an unstarted node with ambient configuration.
func (n *node) dial(host string, port uint16, timeout time.Duration) int {
	if host == "" || !utf8.ValidString(host) || strings.ContainsRune(host, 0) || port == 0 {
		return resultInvalid
	}
	ctx, cancel, result := n.whenStarted(timeout)
	if result != 0 {
		return result
	}
	defer cancel()
	if !n.awaitRunning(ctx) {
		return n.failed(ctx, resultTimeout)
	}
	// Resolves MagicDNS names through the tailnet's DNS configuration.
	dialer := n.server.Sys().Dialer.Get()
	target, _, err := dialer.UserDialPlan(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(int(port))))
	if err != nil {
		return n.failed(ctx, resultUnknownHost)
	}
	// Netstack reaches exactly the peers and subnet routes in the network map. Upstream would dial
	// other addresses, including Tailscale IPs of unknown nodes, through the device's network.
	if !dialer.UseNetstackForIP(target.Addr()) {
		return resultNoRoute
	}
	conn, err := dialer.NetstackDialTCP(ctx, target)
	if err != nil {
		return n.failed(ctx, resultDialFailed)
	}
	fd, err := relay(conn)
	if err != nil {
		_ = conn.Close()
		return n.failed(ctx, resultDialFailed)
	}
	return fd
}

// logout asks control to expire this node's key, then deletes the node's profile from its state,
// leaving the node in NeedsLogin. It returns 0, also when the node was not logged in, or a failure.
func (n *node) logout(timeout time.Duration) int {
	ctx, cancel, result := n.whenStarted(timeout)
	if result != 0 {
		return result
	}
	defer cancel()
	client, err := n.server.LocalClient()
	if err != nil {
		return n.failed(ctx, resultLogoutFailed)
	}
	if err := client.Logout(ctx); err != nil {
		return n.failed(ctx, resultLogoutFailed)
	}
	return 0
}

// login starts an interactive login, also after a logout or key expiry, and also when logged in, to
// re-authenticate. The new login URL is published with the status. It returns 0 or a failure.
func (n *node) login() int {
	ctx, cancel, result := n.whenStarted(math.MaxInt64) // Neither call waits for the network.
	if result != 0 {
		return result
	}
	defer cancel()
	client, err := n.server.LocalClient()
	if err != nil {
		return n.failed(ctx, resultLoginFailed)
	}
	// As upstream Server.Start. A logout replaced the prefs with defaults, including Tailscale's
	// control URL and the device's hostname, and the control client keeps the URL it started with.
	prefs := ipn.NewPrefs()
	prefs.Hostname, prefs.ControlURL, prefs.WantRunning = n.server.Hostname, n.server.ControlURL, true
	if err := client.Start(ctx, ipn.Options{UpdatePrefs: prefs}); err != nil {
		return n.failed(ctx, resultLoginFailed)
	}
	if err := client.StartLoginInteractive(ctx); err != nil {
		return n.failed(ctx, resultLoginFailed)
	}
	return 0
}

// awaitRunning waits until the node runs, returning false when ctx ends first.
func (n *node) awaitRunning(ctx context.Context) bool {
	for {
		n.mu.Lock()
		state, changed := n.status.state, n.changed
		n.mu.Unlock()
		if state == ipn.Running {
			return true
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return false
		}
	}
}

// relay copies between conn and one end of a new socket pair, returning the other end's
// descriptor. The peer's EOF reaches the app as end of stream while the app may keep writing.
// The app's EOF (closing the descriptor or shutting down its output) ends the connection, so
// connections never outlive the app's descriptor; half-closing towards the peer is not supported.
func relay(conn net.Conn) (int, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	file := os.NewFile(uintptr(fds[0]), "relay")
	local, err := net.FileConn(file) // A duplicate descriptor.
	_ = file.Close()
	if err != nil {
		_ = syscall.Close(fds[1])
		return -1, err
	}
	end := sync.OnceFunc(func() {
		_ = conn.Close() // Also unblocks the other copy.
		_ = local.Close()
	})
	go func() {
		if _, err := io.Copy(local, conn); err != nil || local.(*net.UnixConn).CloseWrite() != nil {
			end()
		}
	}()
	go func() {
		_, _ = io.Copy(conn, local)
		end()
	}()
	return fds[1], nil
}

// Java enum names, in TsnetStatus.State. InUseOtherUser is Windows-only.
var javaStates = map[ipn.State]string{
	ipn.NeedsLogin:       "NEEDS_LOGIN",
	ipn.NeedsMachineAuth: "NEEDS_MACHINE_AUTH",
	ipn.Stopped:          "STOPPED",
	ipn.Starting:         "STARTING",
	ipn.Running:          "RUNNING",
}

// record is "version\nSTATE\nREVOCATION\nloginURL\naddresses\nerror", with comma-separated address
// literals. All printable ASCII apart from the separators.
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
	return fmt.Sprintf("%d\n%s\n%s\n%s\n%s\n%s", version, state, s.revocation, s.loginURL, strings.Join(addresses, ","), s.err)
}
