package main

import (
	"cmp"
	"context"
	"io"
	"math"
	"net"
	"net/netip"
	"net/url"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tsconst"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
)

// node publishes status and dials through the tailnet. Closing cancels calls and connections.
type node struct {
	server *tsnet.Server
	client *local.Client

	lifecycle sync.RWMutex   // Held for reading by calls, and for writing by close.
	watchers  sync.WaitGroup // Status goroutines, which use the server until ctx ends.

	ctx    context.Context // Cancelled when the node closes.
	cancel context.CancelFunc

	mu        sync.Mutex
	access    context.Context // Ends when the node leaves Running for any state but Starting; renewed on Running.
	endAccess context.CancelFunc
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
	statusRead
}

// statusRead holds what refreshStatus reads from the latest network map, as printable ASCII.
type statusRead struct {
	dnsName, tailnet, loginName string // This node's; the DNS name without a trailing dot.
	peers                       string // The record's peer lines, each preceded by a newline.
}

func newNode(server *tsnet.Server) *node {
	ctx, cancel := context.WithCancel(context.Background())
	access, endAccess := context.WithCancel(ctx)
	return &node{server: server, ctx: ctx, cancel: cancel, access: access, endAccess: endAccess, changed: make(chan struct{})}
}

// start runs before publication. TailnetNode validates config: "directory\x00hostname\x00controlURL".
func (n *node) start(config string) int {
	directory, rest, _ := strings.Cut(config, "\x00")
	hostname, controlURL, _ := strings.Cut(rest, "\x00")
	// tsnet must not use a host-process credential or control URL.
	if os.Getenv("TS_AUTHKEY") != "" || os.Getenv("TS_AUTH_KEY") != "" {
		return resultAuthEnvironment
	}
	n.server.Dir, n.server.Hostname, n.server.ControlURL = directory, hostname, cmp.Or(controlURL, ipn.DefaultControlURL)
	var err error
	if n.client, err = n.server.LocalClient(); err != nil { // Starts the server.
		return resultStartFailed
	}
	refresh := make(chan struct{}, 1)
	n.watchers.Go(func() { n.watchStatus(refresh) })
	n.watchers.Go(func() { n.refreshStatus(refresh) })
	context.AfterFunc(n.ctx, watchDefaultNetwork(n.server))
	return 0
}

// close ends waiters, calls and relayed connections, and closes the server once calls return.
func (n *node) close() {
	n.cancel()
	n.lifecycle.Lock()
	defer n.lifecycle.Unlock()
	n.watchers.Wait()
	_ = n.server.Close()
}

// watchStatus resumes with current state after disconnects. Refresh names and peers separately:
// large status reads can stall the watch. Every map sends SelfChange; among peer patches, only
// online changes affect our records. Other peer changes arrive as full nodes.
func (n *node) watchStatus(refresh chan<- struct{}) {
	for n.ctx.Err() == nil {
		if watcher, err := n.client.WatchIPNBus(n.ctx, ipn.NotifyInitialState|ipn.NotifyInitialStatus|ipn.NotifyPeerPatches); err == nil {
			for {
				notify, err := watcher.Next()
				if err != nil {
					break
				}
				n.publish(notify, nil)
				if notify.InitialStatus != nil || notify.State != nil || notify.SelfChange != nil ||
					len(notify.PeersChanged)+len(notify.PeersRemoved) > 0 ||
					slices.ContainsFunc(notify.PeerChangedPatch, func(c *tailcfg.PeerChange) bool { return c.Online != nil }) {
					select {
					case refresh <- struct{}{}:
					default: // A refresh is pending, and will read this change.
					}
				}
			}
			_ = watcher.Close()
		}
		select {
		case <-n.ctx.Done():
		case <-time.After(time.Second):
		}
	}
}

// refreshStatus reads names and peers from the current map, which logout clears.
func (n *node) refreshStatus(refresh <-chan struct{}) {
	for {
		select {
		case <-n.ctx.Done():
			return
		case <-refresh:
		}
		if status, err := n.client.Status(n.ctx); err == nil {
			n.publish(ipn.Notify{}, readStatus(status))
		}
	}
}

// readStatus returns this node's names and the record's peer lines.
func readStatus(status *ipnstate.Status) *statusRead {
	lines := make([]string, 0, len(status.Peer))
	for _, peer := range status.Peer {
		if line := peerLine(peer); line != "" {
			lines = append(lines, line)
		}
	}
	slices.Sort(lines)
	read := &statusRead{peers: strings.Join(lines, "")}
	if self := status.Self; self != nil {
		read.dnsName = printable(strings.TrimSuffix(self.DNSName, "."), 255)
		read.loginName = printable(status.User[self.UserID].LoginName, 255)
	}
	if tailnet := status.CurrentTailnet; tailnet != nil {
		read.tailnet = printable(tailnet.Name, 255)
	}
	return read
}

// peerLine returns "\nname\tOS\tonline\taddresses\tSSH host keys", with comma-separated lists.
// Like "tailscale status", it hides sharees and Mullvad exit nodes. Keys must be printable without commas.
func peerLine(peer *ipnstate.PeerStatus) string {
	name := strings.TrimSuffix(peer.DNSName, ".")
	if peer.ShareeNode || name == "" || strings.HasSuffix(name, ".mullvad.ts.net") || len(peer.TailscaleIPs) == 0 {
		return ""
	}
	keys := make([]string, 0, len(peer.SSH_HostKeys))
	for _, key := range peer.SSH_HostKeys {
		if key = strings.TrimSpace(key); key != "" && key == printable(key, 8192) && !strings.Contains(key, ",") {
			keys = append(keys, key)
		}
	}
	return "\n" + printable(name, 255) + "\t" + printable(peer.OS, 32) + "\t" + strconv.FormatBool(peer.Online) +
		"\t" + addressList(peer.TailscaleIPs...) + "\t" + strings.Join(keys, ",")
}

// publish applies notify and an optional status read. Lock backend reads against both publishers.
func (n *node) publish(notify ipn.Notify, read *statusRead) {
	n.mu.Lock()
	defer n.mu.Unlock()
	status := n.status
	status.addresses = addressList(n.server.TailscaleIPs())
	if read != nil {
		status.statusRead = *read
	}
	if notify.State != nil {
		status.state = *notify.State
		switch status.state {
		case ipn.NoState: // A new profile, as after a logout, has no network map or pending login yet.
			n.keyExpiry = time.Time{}
			status.loginURL = ""
		case ipn.Running:
			status.err = ""
			if n.access.Err() != nil {
				n.access, n.endAccess = context.WithCancel(n.ctx)
			}
		}
		// Connections end with tailnet access. Starting is transient, and upstream stays Running
		// through network changes.
		if status.state != ipn.Running && status.state != ipn.Starting {
			n.endAccess()
		}
	}
	if notify.ErrMessage != nil && *notify.ErrMessage != watchFellBehind {
		status.err = printable(*notify.ErrMessage, 256)
	}
	if notify.BrowseToURL != nil {
		status.loginURL = loginURL(*notify.BrowseToURL)
	}
	// A watch has no initial SelfChange. Self has a key only with a network map.
	self, expiry := n.selfKey, n.keyExpiry
	if initial := notify.InitialStatus; initial != nil && initial.Self != nil && !initial.Self.PublicKey.IsZero() {
		self, expiry = initial.Self.PublicKey, time.Time{}
		if initial.Self.KeyExpiry != nil {
			expiry = *initial.Self.KeyExpiry
		}
	}
	if notify.SelfChange != nil {
		self, expiry = notify.SelfChange.Key, notify.SelfChange.KeyExpiry
	}
	// A changed node key confirms the new login is saved. LoginFinished is too early, and state
	// stays Running during re-authentication.
	if self != n.selfKey {
		status.loginURL = ""
	}
	n.selfKey, n.keyExpiry = self, expiry
	// Upstream reports logout, key expiry and removal alike as NeedsLogin.
	status.revocation = ""
	if status.state == ipn.NeedsLogin {
		// Upstream sets the warning before reporting NeedsLogin; health notifications may come later.
		if _, removed := n.server.Sys().HealthTracker.Get().CurrentState().Warnings[tsconst.HealthWarnableNodeRemoved]; removed {
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

// ipnlocal.watchIPNBusFellBehindMessage ends a watch; it is not a backend error.
const watchFellBehind = "IPN bus consumer fell behind; closing watch"

// printable returns at most limit bytes of s, replacing bytes other than printable ASCII with '?'.
func printable(s string, limit int) string {
	if len(s) <= limit && strings.IndexFunc(s, func(r rune) bool { return r < ' ' || r > '~' }) < 0 {
		return s
	}
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

// await returns the status record once its version differs from after, or "" on timeout without a
// change so Java can keep its existing snapshot. It returns false once the node is closed.
func (n *node) await(after uint64, timeout time.Duration) (string, bool) {
	if n == nil {
		return "", false
	}
	ctx, cancel := context.WithTimeout(n.ctx, timeout)
	defer cancel()
	status, version, _ := n.wait(ctx, func(_ nodeStatus, version uint64) bool { return version != after })
	if n.ctx.Err() != nil {
		return "", false
	}
	if version == after {
		return "", true
	}
	return status.record(version), true
}

// wait returns the latest status, version and access lifetime once done accepts them or ctx ends.
func (n *node) wait(ctx context.Context, done func(nodeStatus, uint64) bool) (nodeStatus, uint64, context.Context) {
	for {
		n.mu.Lock()
		status, version, changed, access := n.status, n.version, n.changed, n.access
		n.mu.Unlock()
		if done(status, version) || ctx.Err() != nil {
			return status, version, access
		}
		select {
		case <-changed:
		case <-ctx.Done():
		}
	}
}

// Negative results, which TailnetNode.check maps to exceptions.
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

// whenOpen bounds a call by timeout and node lifetime. Close waits until the returned function runs.
func (n *node) whenOpen(timeout time.Duration) (context.Context, func(), int) {
	// Only close locks for writing, after cancelling: fail at once rather than wait for shutdown.
	// A call racing close runs with a cancelled context.
	if n == nil || n.ctx.Err() != nil || !n.lifecycle.TryRLock() {
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

// dial owns fd, a connected stream socket, and closes it on failure. Timeout includes waiting for
// Running. TailnetNode validates host and port.
func (n *node) dial(host string, port uint16, fd int, timeout time.Duration) (result int) {
	app := streamSocket(fd)
	if app == nil {
		return resultInvalid
	}
	defer func() {
		if result != 0 {
			_ = app.Close()
		}
	}()
	ctx, done, result := n.whenOpen(timeout)
	if result != 0 {
		return result
	}
	defer done()
	// Capture access with Running: a later logout/login must not give this call new access.
	status, _, access := n.wait(ctx, func(s nodeStatus, _ uint64) bool { return s.state == ipn.Running })
	if status.state != ipn.Running {
		return n.failed(ctx, resultTimeout)
	}
	dialCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	unwatch := context.AfterFunc(access, cancel)
	defer unwatch()
	// Peer names resolve from the network map, others only through the tailnet's DNS: the
	// dns_lookup.go patch keeps them from the device's resolver.
	dialer := n.server.Sys().Dialer.Get()
	target, _, err := dialer.UserDialPlan(dialCtx, "tcp", net.JoinHostPort(host, strconv.Itoa(int(port))))
	if err != nil {
		if access.Err() != nil {
			return n.failed(ctx, resultDialFailed)
		}
		return n.failed(ctx, resultUnknownHost)
	}
	// Netstack reaches exactly the network map's peers and subnet routes. Upstream Server.Dial would
	// dial other addresses, including unknown nodes' Tailscale IPs, through the device's network.
	if !dialer.UseNetstackForIP(target.Addr()) {
		return n.failed(ctx, resultNoRoute)
	}
	conn, err := dialer.NetstackDialTCP(dialCtx, target)
	if err != nil {
		return n.failed(ctx, resultDialFailed)
	}
	if access.Err() != nil {
		_ = conn.Close()
		return n.failed(ctx, resultDialFailed)
	}
	relay(access, conn, app)
	return 0
}

// streamSocket consumes fd, returning a connected TCP/Unix stream or nil after closing it.
func streamSocket(fd int) net.Conn {
	file := os.NewFile(uintptr(fd), "socket")
	conn, err := net.FileConn(file) // A duplicate descriptor.
	_ = file.Close()
	if err != nil {
		return nil
	}
	if network := conn.LocalAddr().Network(); (network != "tcp" && network != "unix") || conn.RemoteAddr() == nil {
		_ = conn.Close()
		return nil
	}
	return conn
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

// login starts or renews browser authorization; status carries the URL.
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
	// Logout stops the node and, on success, resets prefs. Restore them as Server.Start does.
	// Skip Start when renewing: it restarts control. Start ignores cancellation and must finish
	// before Server.Close, or it leaves a control client retrying forever.
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

// relay copies between conn and the app's socket until either side or ctx ends; TailnetNode.dial
// documents the stream semantics. Failures and the end of ctx reset a TCP socket; a Unix socket reads
// end of stream, or a reset if data it wrote is unread.
func relay(ctx context.Context, conn, app net.Conn) {
	var once sync.Once
	end := func(reset bool) {
		once.Do(func() {
			if tcp, ok := app.(*net.TCPConn); ok && reset {
				_ = tcp.SetLinger(0)
			}
			_ = conn.Close() // Also unblocks the other copy.
			_ = app.Close()
		})
	}
	// Closing conn alone cannot interrupt a copy blocked writing to the app's socket.
	unwatch := context.AfterFunc(ctx, func() { end(true) })
	go func() {
		if _, err := io.Copy(app, conn); err != nil || app.(interface{ CloseWrite() error }).CloseWrite() != nil {
			end(true) // The other copy then ends and unwatches.
		}
	}()
	go func() {
		_, err := io.Copy(conn, app)
		unwatch()
		end(err != nil)
	}()
}

// Java enum names, in NodeStatus.State, other than NO_STATE. InUseOtherUser is Windows-only.
var javaStates = map[ipn.State]string{
	ipn.NeedsLogin:       "NEEDS_LOGIN",
	ipn.NeedsMachineAuth: "NEEDS_MACHINE_AUTH",
	ipn.Stopped:          "STOPPED",
	ipn.Starting:         "STARTING",
	ipn.Running:          "RUNNING",
}

// record is "version\nSTATE\nREVOCATION\nloginURL\naddresses\nerror\ndnsName\ntailnet\nloginName",
// with comma-separated address literals, followed by the peer lines. All printable ASCII apart from
// the separators.
func (s nodeStatus) record(version uint64) string {
	return strconv.FormatUint(version, 10) + "\n" + cmp.Or(javaStates[s.state], "NO_STATE") + "\n" +
		s.revocation + "\n" + s.loginURL + "\n" + s.addresses + "\n" + s.err + "\n" +
		s.dnsName + "\n" + s.tailnet + "\n" + s.loginName + s.peers
}

// addressList returns the valid addresses, IPv4 first, comma-separated.
func addressList(ips ...netip.Addr) string {
	ips = slices.Clone(ips)
	slices.SortFunc(ips, netip.Addr.Compare)
	list := make([]string, 0, len(ips))
	for _, ip := range ips {
		if ip.IsValid() {
			list = append(list, ip.String())
		}
	}
	return strings.Join(list, ",")
}
