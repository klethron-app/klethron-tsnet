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
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsconst"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
)

// node publishes the latest backend status to waiting callers and dials through the tailnet.
// Calls end when the node closes, so Close waits at most for local work.
type node struct {
	server *tsnet.Server
	client *local.Client

	lifecycle sync.RWMutex // Held for reading by calls, and for writing by close.
	stop      func()       // Stops status watching and default-network reports once started.

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
	revocation string // A NodeStatus.Revocation name while NeedsLogin, or "".
	loginURL   string
	addresses  string // This node's, as addressList.
	err        string // The latest backend error since the node last ran, as printable ASCII.
	peers      string // The record's peer lines, each preceded by a newline.
}

func newNode(server *tsnet.Server) *node {
	ctx, cancel := context.WithCancel(context.Background())
	return &node{server: server, ctx: ctx, cancel: cancel, changed: make(chan struct{})}
}

// start configures every connection-relevant field explicitly, so the host process environment
// cannot redirect control. config is "directory\x00hostname\x00controlURL"; an empty control URL
// selects Tailscale's. State persists in a file store in the caller's directory. It runs before
// the node is published, so no other call can race it.
func (n *node) start(config string) int {
	fields := strings.Split(config, "\x00")
	if len(fields) != 3 || !utf8.ValidString(config) || !filepath.IsAbs(fields[0]) || fields[1] == "" {
		return resultInvalid
	}
	// tsnet reads an auth key from the environment when none is configured. This library only
	// supports interactive login, so refuse rather than silently use a host-process credential.
	if os.Getenv("TS_AUTHKEY") != "" || os.Getenv("TS_AUTH_KEY") != "" {
		return resultAuthEnvironment
	}
	n.server.Dir, n.server.Hostname, n.server.ControlURL = fields[0], fields[1], fields[2]
	if n.server.ControlURL == "" {
		n.server.ControlURL = ipn.DefaultControlURL
	}
	var err error
	if n.client, err = n.server.LocalClient(); err != nil { // Starts the server.
		return resultStartFailed
	}
	ctx, cancel := context.WithCancel(n.ctx)
	watching := make(chan struct{})
	go func() {
		defer close(watching)
		n.watchStatus(ctx)
	}()
	unwatch := watchDefaultNetwork(n.server)
	n.stop = func() {
		unwatch()
		cancel()
		<-watching
	}
	return 0
}

// close wakes waiters and cancels dials. Closing the server ends relayed connections.
func (n *node) close() {
	n.cancel()
	n.lifecycle.Lock()
	defer n.lifecycle.Unlock()
	if n.stop != nil {
		n.stop()
		n.stop = nil
	}
	_ = n.server.Close()
}

// watchStatus follows the IPN bus until ctx ends. A new watch starts with the current state, self
// node and peers, so re-watching after an unexpected disconnect (such as falling behind) loses nothing
// needed. Peer changes include online changes, as full nodes.
func (n *node) watchStatus(ctx context.Context) {
	for ctx.Err() == nil {
		if watcher, err := n.client.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyInitialStatus|ipn.NotifyPeerChanges); err == nil {
			for {
				notify, err := watcher.Next()
				if err != nil {
					break
				}
				n.publish(notify, peerLines(ctx, n.client, &notify))
			}
			watcher.Close()
		}
		select {
		case <-ctx.Done():
		case <-time.After(time.Second):
		}
	}
}

// peerLines returns the record's peer lines when notify may change them, or nil to keep the
// published ones. Upstream builds the status from its network map, which a logout clears, so
// rather than apply the notified changes, read the status after each change.
func peerLines(ctx context.Context, client *local.Client, notify *ipn.Notify) *string {
	status := notify.InitialStatus
	if status == nil {
		if notify.State == nil && len(notify.PeersChanged) == 0 && len(notify.PeersRemoved) == 0 {
			return nil
		}
		var err error
		if status, err = client.Status(ctx); err != nil {
			return nil
		}
	}
	var lines []string
	for _, peer := range status.Peer {
		if line := peerLine(peer); line != "" {
			lines = append(lines, line)
		}
	}
	slices.Sort(lines)
	joined := strings.Join(lines, "")
	return &joined
}

// peerLine returns "\nname\tOS\tonline\taddresses\tSSH host keys", with comma-separated lists and
// addresses IPv4 first, or "" for peers hidden as by "tailscale status": nodes that may only connect
// to this one, and Mullvad exit nodes, which do not accept connections. It drops host keys that are
// not printable ASCII or contain commas.
func peerLine(peer *ipnstate.PeerStatus) string {
	name := strings.TrimSuffix(peer.DNSName, ".")
	if peer.ShareeNode || name == "" || strings.HasSuffix(name, ".mullvad.ts.net") || len(peer.TailscaleIPs) == 0 {
		return ""
	}
	var keys []string
	for _, key := range peer.SSH_HostKeys {
		if key = strings.TrimSpace(key); key != "" && key == printable(key, 8192) && !strings.Contains(key, ",") {
			keys = append(keys, key)
		}
	}
	return "\n" + strings.Join([]string{printable(name, 255), printable(peer.OS, 32), strconv.FormatBool(peer.Online),
		addressList(peer.TailscaleIPs...), strings.Join(keys, ",")}, "\t")
}

// publish applies notify and, unless nil, the peer lines.
func (n *node) publish(notify ipn.Notify, peers *string) {
	addresses := addressList(n.server.TailscaleIPs())
	// Set before upstream reports NeedsLogin for it; health notifications may arrive later.
	_, removed := n.server.Sys().HealthTracker.Get().CurrentState().Warnings[tsconst.HealthWarnableNodeRemoved]
	n.mu.Lock()
	defer n.mu.Unlock()
	status := n.status
	status.addresses = addresses
	if peers != nil {
		status.peers = *peers
	}
	if notify.State != nil {
		status.state = *notify.State
		switch status.state {
		case ipn.NoState: // A new profile, as after a logout, has no network map or pending login yet.
			n.keyExpiry = time.Time{}
			status.loginURL = ""
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
	setSelf := func(self key.NodePublic, expiry time.Time) {
		n.keyExpiry = expiry
		if self != n.selfKey {
			n.selfKey = self
			status.loginURL = ""
		}
	}
	// A watch has no initial SelfChange. Self has a key only with a network map.
	if initial := notify.InitialStatus; initial != nil && initial.Self != nil && !initial.Self.PublicKey.IsZero() {
		var expiry time.Time
		if initial.Self.KeyExpiry != nil {
			expiry = *initial.Self.KeyExpiry
		}
		setSelf(initial.Self.PublicKey, expiry)
	}
	if self := notify.SelfChange; self != nil {
		setSelf(self.Key, self.KeyExpiry)
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

// loginURL returns raw if it is an absolute HTTP(S) URL of printable ASCII without spaces, otherwise "".
func loginURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" ||
		strings.ContainsFunc(raw, func(r rune) bool { return r <= ' ' || r > '~' }) {
		return ""
	}
	return raw
}

// await returns the status record once its version differs from after, or the timeout elapses.
// It returns false once the node is closed.
func (n *node) await(after uint64, timeout time.Duration) (string, bool) {
	ctx, cancel := context.WithTimeout(n.ctx, timeout)
	defer cancel()
	status, version := n.wait(ctx, func(_ nodeStatus, version uint64) bool { return version != after })
	return status.record(version), n.ctx.Err() == nil
}

// wait returns the latest status and its version once done accepts them or ctx ends.
func (n *node) wait(ctx context.Context, done func(nodeStatus, uint64) bool) (nodeStatus, uint64) {
	for {
		n.mu.Lock()
		status, version, changed := n.status, n.version, n.changed
		n.mu.Unlock()
		if done(status, version) || ctx.Err() != nil {
			return status, version
		}
		select {
		case <-changed:
		case <-ctx.Done():
		}
	}
}

// Failures of calls that otherwise return a file descriptor or 0; TailnetNode.check maps them to
// exceptions.
const (
	resultClosed = -1 - iota
	resultInvalid
	resultUnknownHost
	resultNoRoute
	resultTimeout
	resultDialFailed
	resultLogoutFailed
	resultLoginFailed
	resultAuthEnvironment
	resultStartFailed
)

// whenOpen returns a context for a call, ending at the timeout or when the node closes, and a
// function to call when done, or resultClosed. Close waits for the call to finish.
func (n *node) whenOpen(timeout time.Duration) (context.Context, func(), int) {
	n.lifecycle.RLock()
	if n.ctx.Err() != nil {
		n.lifecycle.RUnlock()
		return nil, nil, resultClosed
	}
	ctx, cancel := context.WithTimeout(n.ctx, timeout)
	return ctx, func() {
		cancel()
		n.lifecycle.RUnlock()
	}, 0
}

// failed maps a failure of a call with ctx from whenOpen to closed or timed out, if applicable.
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
// node to run. Unlike upstream Server.Dial, it never connects through the device's own network.
func (n *node) dial(host string, port uint16, timeout time.Duration) int {
	if host == "" || !utf8.ValidString(host) || strings.ContainsRune(host, 0) || port == 0 {
		return resultInvalid
	}
	ctx, done, result := n.whenOpen(timeout)
	if result != 0 {
		return result
	}
	defer done()
	status, _ := n.wait(ctx, func(s nodeStatus, _ uint64) bool { return s.state == ipn.Running })
	if status.state != ipn.Running {
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
		return n.failed(ctx, resultNoRoute)
	}
	conn, err := dialer.NetstackDialTCP(ctx, target)
	if err != nil {
		return n.failed(ctx, resultDialFailed)
	}
	fd, err := relay(n.ctx, conn)
	if err != nil {
		_ = conn.Close()
		return n.failed(ctx, resultDialFailed)
	}
	return fd
}

// logout asks control to expire this node's key, then deletes the node's profile from its state,
// leaving the node in NeedsLogin. It returns 0, also when the node was not logged in, or a failure.
func (n *node) logout(timeout time.Duration) int {
	ctx, done, result := n.whenOpen(timeout)
	if result != 0 {
		return result
	}
	defer done()
	if err := n.client.Logout(ctx); err != nil {
		return n.failed(ctx, resultLogoutFailed)
	}
	return 0
}

// login starts an interactive login, also after a logout or key expiry, and also when logged in, to
// re-authenticate. The new login URL is published with the status. It returns 0 or a failure.
func (n *node) login() int {
	ctx, done, result := n.whenOpen(math.MaxInt64) // Neither call waits for the network.
	if result != 0 {
		return result
	}
	defer done()
	current, err := n.client.GetPrefs(ctx)
	if err != nil {
		return n.failed(ctx, resultLoginFailed)
	}
	// A logout, even a failed one, stops the node, and a successful one resets the prefs to
	// defaults, including Tailscale's control URL and the device's hostname. Restore them as
	// upstream Server.Start; the control client keeps the URL it started with. Start also restarts
	// the control client, which would interrupt a running node, so skip it when renewing a login.
	// LocalAPI's Start ignores cancellation, and run after Server.Close it would create a control
	// client that retries forever, so wait for it even when the node closes: it is local work.
	if current.LoggedOut || !current.WantRunning || current.ControlURL != n.server.ControlURL ||
		current.Hostname != n.server.Hostname {
		prefs := ipn.NewPrefs()
		prefs.Hostname, prefs.ControlURL, prefs.WantRunning = n.server.Hostname, n.server.ControlURL, true
		if err := n.client.Start(context.WithoutCancel(ctx), ipn.Options{UpdatePrefs: prefs}); err != nil {
			return n.failed(ctx, resultLoginFailed)
		}
	}
	if err := n.client.StartLoginInteractive(ctx); err != nil {
		return n.failed(ctx, resultLoginFailed)
	}
	return 0
}

// relay copies between conn and one end of a new socket pair, returning the other end's
// descriptor. The peer's EOF reaches the app as end of stream while the app may keep writing.
// Unix sockets cannot signal a reset, so failures and closing ctx also appear as end of stream.
// The app's EOF (closing the descriptor or shutting down its output) closes the connection once
// its written data reaches the peer, or resets it if received data is unread; half-closing
// towards the peer is not supported.
func relay(ctx context.Context, conn net.Conn) (int, error) {
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
	// Closing conn alone cannot interrupt a copy blocked writing to the app's socket.
	unwatch := context.AfterFunc(ctx, end)
	finish := func() {
		unwatch()
		end()
	}
	go func() {
		if _, err := io.Copy(local, conn); err != nil || local.(*net.UnixConn).CloseWrite() != nil {
			finish()
		}
	}()
	go func() {
		_, _ = io.Copy(conn, local)
		finish()
	}()
	return fds[1], nil
}

// Java enum names, in NodeStatus.State. InUseOtherUser is Windows-only.
var javaStates = map[ipn.State]string{
	ipn.NeedsLogin:       "NEEDS_LOGIN",
	ipn.NeedsMachineAuth: "NEEDS_MACHINE_AUTH",
	ipn.Stopped:          "STOPPED",
	ipn.Starting:         "STARTING",
	ipn.Running:          "RUNNING",
}

// record is "version\nSTATE\nREVOCATION\nloginURL\naddresses\nerror", with comma-separated address
// literals, followed by the peer lines. All printable ASCII apart from the separators.
func (s nodeStatus) record(version uint64) string {
	state, ok := javaStates[s.state]
	if !ok {
		state = "NO_STATE"
	}
	return fmt.Sprintf("%d\n%s\n%s\n%s\n%s\n%s%s", version, state, s.revocation, s.loginURL, s.addresses, s.err, s.peers)
}

// addressList returns the valid addresses, IPv4 first, comma-separated.
func addressList(ips ...netip.Addr) string {
	var list []string
	for _, ip := range slices.SortedFunc(slices.Values(ips), netip.Addr.Compare) {
		if ip.IsValid() {
			list = append(list, ip.String())
		}
	}
	return strings.Join(list, ",")
}
