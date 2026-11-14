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
	"tailscale.com/net/netx"
	"tailscale.com/tailcfg"
	"tailscale.com/tsconst"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
)

type node struct {
	server *tsnet.Server
	client *local.Client
	store  *javaStore

	lifecycle    sync.RWMutex   // Held for reading by calls, and for writing by close.
	watchers     sync.WaitGroup // Status goroutines, which use the server until ctx ends.
	auth         chan struct{}  // Serializes login/logout, including their reads and network requests.
	statusReadMu sync.Mutex     // Serializes metadata reads through publication, including entry to Running.

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
	addresses  [2]netip.Addr // This node's IPv4 and IPv6 addresses.
	err        string        // The latest backend error since the node last ran, as printable ASCII.
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
	return &node{server: server, ctx: ctx, cancel: cancel, access: access, endAccess: endAccess,
		changed: make(chan struct{}), auth: make(chan struct{}, 1)}
}

// start runs before publication. TailnetNode validates config: "hostname\x00controlURL".
func (n *node) start(config string) int {
	hostname, controlURL, _ := strings.Cut(config, "\x00")
	// tsnet must not use a host-process credential or control URL.
	if os.Getenv("TS_AUTHKEY") != "" || os.Getenv("TS_AUTH_KEY") != "" {
		return resultAuthEnvironment
	}
	n.server.Hostname, n.server.ControlURL = hostname, cmp.Or(controlURL, ipn.DefaultControlURL)
	var err error
	if n.client, err = n.server.LocalClient(); err != nil { // Starts the server.
		return n.failed(n.ctx, resultStartFailed)
	}
	if n.store != nil && n.store.failed.Load() {
		return resultStoreFailed
	}
	if n.store != nil {
		n.server.Sys().MagicSock.Get().SetPeerUDPDeniedCallback(n.store.localNetworkDenied)
	}
	refresh := make(chan struct{}, 1)
	n.watchers.Go(func() { n.watchStatus(refresh) })
	n.watchers.Go(func() { n.refreshStatus(refresh) })
	return 0
}

func (n *node) close() {
	n.cancel()
	n.lifecycle.Lock()
	defer n.lifecycle.Unlock()
	n.watchers.Wait()
	_ = n.server.Close()
	if n.store != nil {
		n.store.close()
	}
}

// watchStatus resumes with current state after disconnects. Refresh names and peers separately:
// large status reads can stall the watch. Full maps send SelfChange; profile-only updates can
// change the login name. Among peer patches, only online changes affect our records.
func (n *node) watchStatus(refresh chan<- struct{}) {
	defer watchDefaultNetwork(n.server)()
	for n.ctx.Err() == nil {
		if watcher, err := n.client.WatchIPNBus(n.ctx, ipn.NotifyInitialState|ipn.NotifyInitialStatus|ipn.NotifyPeerPatches); err == nil {
			for {
				notify, err := watcher.Next()
				if err != nil || (notify.ErrMessage != nil && *notify.ErrMessage == watchFellBehind) {
					break
				}
				if notify.State != nil && *notify.State == ipn.Running {
					n.publishStatus(notify)
					continue
				}
				n.publish(notify, nil)
				if notify.InitialStatus != nil || notify.State != nil || notify.SelfChange != nil ||
					len(notify.PeersChanged)+len(notify.PeersRemoved)+len(notify.UserProfiles) > 0 ||
					slices.ContainsFunc(notify.PeerChangedPatch, func(c *tailcfg.PeerChange) bool { return c.Online != nil }) {
					select {
					case refresh <- struct{}{}:
					default: // A refresh is pending, and will read this change.
					}
				}
			}
			_ = watcher.Close()
		}
		// A gap can hide logout, expiry or a different login. Existing dials and relays
		// must not inherit access from the next snapshot, even if it is already Running.
		n.mu.Lock()
		n.endAccess()
		n.mu.Unlock()
		select {
		case <-n.ctx.Done():
		case <-time.After(time.Second):
		}
	}
}

func (n *node) refreshStatus(refresh <-chan struct{}) {
	for {
		select {
		case <-n.ctx.Done():
			return
		case <-refresh:
		}
		n.publishStatus(ipn.Notify{})
	}
}

// Running must include fresh peer metadata. Other notifications do not take this lock, so background
// metadata refreshes do not delay their publication.
func (n *node) publishStatus(notify ipn.Notify) {
	n.statusReadMu.Lock()
	defer n.statusReadMu.Unlock()
	n.publish(notify, readStatus(n.server.Status()))
}

func readStatus(status *ipnstate.Status) *statusRead {
	lines := make([]string, 0, len(status.Peer))
	for _, peer := range status.Peer {
		name := strings.TrimSuffix(peer.DNSName, ".")
		// Like tailscale status, hide sharees and Mullvad exit nodes.
		if peer.ShareeNode || name == "" || strings.HasSuffix(name, ".mullvad.ts.net") || len(peer.TailscaleIPs) == 0 {
			continue
		}
		keys := make([]string, 0, len(peer.SSH_HostKeys))
		for _, key := range peer.SSH_HostKeys {
			if key = strings.TrimSpace(key); key != "" && key == printable(key, 8192) && !strings.Contains(key, ",") {
				keys = append(keys, key)
			}
		}
		lines = append(lines, "\n"+printable(name, 255)+"\t"+printable(peer.OS, 32)+"\t"+strconv.FormatBool(peer.Online)+
			"\t"+addressList(peer.TailscaleIPs...)+"\t"+strings.Join(keys, ","))
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

// publish applies notify and an optional status read. Lock backend reads against both publishers.
func (n *node) publish(notify ipn.Notify, read *statusRead) {
	n.mu.Lock()
	defer n.mu.Unlock()
	status := n.status
	status.addresses[0], status.addresses[1] = n.server.TailscaleIPs()
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
	if notify.ErrMessage != nil {
		status.err = printable(*notify.ErrMessage, 256)
	}
	// Initial snapshots have no SelfChange.
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
	// A new key confirms persistence; LoginFinished is too early. Running can span a change of account.
	if self != n.selfKey {
		status.loginURL = ""
		if !n.selfKey.IsZero() {
			n.endAccess()
		}
		if status.state == ipn.Running && !self.IsZero() && n.access.Err() != nil {
			n.access, n.endAccess = context.WithCancel(n.ctx)
		}
	}
	n.selfKey, n.keyExpiry = self, expiry
	// Running snapshots omit BrowseToURL, even during renewal. Replace stale URLs after a watch gap.
	if initial := notify.InitialStatus; initial != nil && initial.AuthURL != "" {
		status.loginURL = loginURL(n.server.ControlURL, initial.AuthURL)
	}
	if notify.BrowseToURL != nil {
		status.loginURL = loginURL(n.server.ControlURL, *notify.BrowseToURL)
	}
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

// printable limits the record field to printable ASCII and at most limit bytes.
func printable(s string, limit int) string {
	if len(s) > limit {
		s = strings.Clone(s[:limit])
	}
	return strings.Map(func(r rune) rune {
		if r < ' ' || r > '~' {
			return '?'
		}
		return r
	}, s)
}

// loginURL validates here as well as upstream: initial watch snapshots bypass upstream's
// browser notification filter. Login URLs are bearer credentials, including in development.
func loginURL(control, raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" || u.User != nil ||
		strings.ContainsFunc(raw, func(r rune) bool { return r <= ' ' || r > '~' }) {
		return ""
	}
	if ipn.IsLoginServerSynonym(control) && u.Hostname() != "tailscale.com" && !strings.HasSuffix(u.Hostname(), ".tailscale.com") {
		return ""
	}
	if u.Scheme != "https" {
		// Only the explicitly selected loopback test control server may use plaintext.
		server, err := url.Parse(control)
		if err != nil || u.Scheme != "http" || server.Scheme != "http" || u.Host != server.Host {
			return ""
		}
		ip, err := netip.ParseAddr(u.Hostname())
		if err != nil || !ip.IsLoopback() {
			return ""
		}
	}
	return raw
}

// await returns the status record once its version differs from after, or "" on timeout without a
// change so Java can keep its existing snapshot. It returns false once the node is closed.
func (n *node) await(after uint64, timeout time.Duration) (string, bool) {
	if n == nil {
		return "", false
	}
	n.mu.Lock()
	status, version := n.status, n.version
	n.mu.Unlock()
	if version == after && timeout > 0 {
		ctx, cancel := context.WithTimeout(n.ctx, timeout)
		defer cancel()
		status, version, _ = n.wait(ctx, func(_ nodeStatus, version uint64) bool { return version != after })
	}
	if n.store != nil && n.store.failed.Load() {
		return "!", true
	}
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
	resultStoreFailed
)

// whenOpen bounds a call by timeout and node lifetime. Close waits until the returned function runs.
func (n *node) whenOpen(timeout time.Duration) (context.Context, func(), int) {
	// Only close locks for writing, after cancelling: fail at once rather than wait for shutdown.
	// A call racing close runs with a cancelled context.
	if n == nil || n.ctx.Err() != nil || !n.lifecycle.TryRLock() {
		return nil, nil, n.failed(context.Background(), resultClosed)
	}
	ctx, cancel := context.WithTimeout(n.ctx, timeout)
	return ctx, func() {
		cancel()
		n.lifecycle.RUnlock()
	}, 0
}

// failed maps a failure of a call with ctx from whenOpen to closed or timed out, if applicable.
func (n *node) failed(ctx context.Context, result int) int {
	if n != nil && n.store != nil && n.store.failed.Load() {
		return resultStoreFailed
	}
	if n == nil || n.ctx.Err() != nil {
		return resultClosed
	}
	if ctx.Err() != nil {
		return resultTimeout
	}
	return result
}

// dial owns fd, a connected TCP socket, and closes it on failure. Timeout includes waiting for
// Running. TailnetNode validates host and port.
func (n *node) dial(host string, port uint16, fd int, timeout time.Duration) (result int) {
	app := tcpSocket(fd)
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
	if status.state != ipn.Running || ctx.Err() != nil {
		return n.failed(ctx, resultTimeout)
	}
	if access.Err() != nil {
		return n.failed(ctx, resultDialFailed)
	}
	dialCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	unwatch := context.AfterFunc(access, cancel)
	defer unwatch()
	// Peer names resolve from the network map, others only through the tailnet's DNS: the
	// dns_lookup.go patch keeps them from the device's resolver.
	dialer := n.server.Sys().Dialer.Get()
	addresses, err := n.server.LookupIPForDial(dialCtx, host)
	if err != nil {
		if access.Err() != nil {
			return n.failed(ctx, resultDialFailed)
		}
		return n.failed(ctx, resultUnknownHost)
	}
	// Netstack reaches exactly the network map's peers and subnet routes. Upstream Server.Dial would
	// dial other addresses, including unknown nodes' Tailscale IPs, through the device's network.
	var allowed []netip.AddrPort
	for _, address := range addresses {
		target := netip.AddrPortFrom(address.Unmap(), port)
		if dialer.UseNetstackForIP(target.Addr()) && !slices.Contains(allowed, target) {
			allowed = append(allowed, target)
		}
	}
	if len(allowed) == 0 {
		return n.failed(ctx, resultNoRoute)
	}
	dial := dialer.NetstackDialTCP
	var conn net.Conn
	if len(allowed) == 1 {
		conn, err = dial(dialCtx, allowed[0])
	} else {
		conn, err = netx.RaceDial(dialCtx, allowed, func(ctx context.Context, _, address string) (net.Conn, error) {
			// RaceDial cancels losers without waiting for them. Keep each attempt inside the node's
			// lifetime, including an attempt scheduled just as the caller returns or closes the node.
			n.lifecycle.RLock()
			defer n.lifecycle.RUnlock()
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			return dial(ctx, netip.MustParseAddrPort(address))
		}, 300*time.Millisecond)
	}
	if err != nil {
		return n.failed(ctx, resultDialFailed)
	}
	if access.Err() != nil || ctx.Err() != nil {
		_ = conn.Close()
		return n.failed(ctx, resultDialFailed)
	}
	relay(access, conn, app)
	return 0
}

// tcpSocket consumes fd, returning a connected TCP socket or nil after closing it.
func tcpSocket(fd int) *net.TCPConn {
	file := os.NewFile(uintptr(fd), "socket")
	if file == nil {
		return nil
	}
	conn, err := net.FileConn(file) // A duplicate descriptor.
	_ = file.Close()
	if err != nil {
		return nil
	}
	tcp, ok := conn.(*net.TCPConn)
	if !ok || tcp.RemoteAddr() == nil {
		_ = conn.Close()
		return nil
	}
	return tcp
}

func (n *node) logout(timeout time.Duration) int {
	ctx, done, result := n.whenAuthenticating(timeout)
	if result != 0 {
		return result
	}
	defer done()
	// HTTP cancellation can return before its handler stops mutating the backend.
	if err := n.server.Logout(ctx); err != nil {
		return n.failed(ctx, resultLogoutFailed)
	}
	return n.failed(ctx, 0)
}

func (n *node) login() int {
	ctx, done, result := n.whenAuthenticating(math.MaxInt64)
	if result != 0 {
		return result
	}
	defer done()
	current, err := n.client.GetPrefs(ctx)
	if err != nil || ctx.Err() != nil {
		return n.failed(ctx, resultLoginFailed)
	}
	// Restore prefs after logout; skip Start when renewing because it restarts control.
	// Let each mutation finish, but check cancellation before starting the next one.
	if current.LoggedOut || !current.WantRunning || current.ControlURL != n.server.ControlURL ||
		current.Hostname != n.server.Hostname {
		prefs := ipn.NewPrefs()
		prefs.Hostname, prefs.ControlURL, prefs.WantRunning = n.server.Hostname, n.server.ControlURL, true
		if err := n.client.Start(context.WithoutCancel(ctx), ipn.Options{UpdatePrefs: prefs}); err != nil || ctx.Err() != nil {
			return n.failed(ctx, resultLoginFailed)
		}
	}
	if err := n.client.StartLoginInteractive(context.WithoutCancel(ctx)); err != nil {
		return n.failed(ctx, resultLoginFailed)
	}
	return n.failed(ctx, 0)
}

// Authentication changes span multiple backend calls. Waiting for a prior change still obeys
// the caller's timeout and node shutdown; an expired waiter must not start a new operation.
func (n *node) whenAuthenticating(timeout time.Duration) (context.Context, func(), int) {
	ctx, done, result := n.whenOpen(timeout)
	if result != 0 {
		return nil, nil, result
	}
	select {
	case n.auth <- struct{}{}:
		if ctx.Err() == nil {
			return ctx, func() {
				<-n.auth
				done()
			}, 0
		}
		<-n.auth
	case <-ctx.Done():
	}
	result = n.failed(ctx, resultTimeout)
	done()
	return nil, nil, result
}

// relay copies between conn and the app's socket until either side or ctx ends; TailnetNode.connect
// documents the stream semantics. Failures and the end of ctx reset the app's socket.
func relay(ctx context.Context, conn net.Conn, app *net.TCPConn) {
	var once sync.Once
	end := func(reset bool) {
		once.Do(func() {
			if reset {
				_ = app.SetLinger(0)
			}
			_ = conn.Close() // Also unblocks the other copy.
			_ = app.Close()
		})
	}
	// Closing conn alone cannot interrupt a copy blocked writing to the app's socket.
	unwatch := context.AfterFunc(ctx, func() { end(true) })
	go func() {
		if _, err := io.Copy(app, conn); err != nil || app.CloseWrite() != nil {
			end(true) // The other copy then ends and unwatches.
		}
	}()
	go func() {
		_, err := io.Copy(conn, app)
		unwatch()
		end(err != nil)
	}()
}

// Java enum names, in NodeStatus.State, other than INITIALIZING. InUseOtherUser is Windows-only.
var javaStates = map[ipn.State]string{
	ipn.NeedsLogin:       "NEEDS_LOGIN",
	ipn.NeedsMachineAuth: "NEEDS_APPROVAL",
	ipn.Stopped:          "STOPPED",
	ipn.Starting:         "STARTING",
	ipn.Running:          "RUNNING",
}

// record is "version\nSTATE\nREVOCATION\nloginURL\naddresses\nerror\ndnsName\ntailnet\nloginName",
// then "\nname\tOS\tonline\taddresses\tSSH host keys" per peer. Lists are comma-separated; fields are ASCII.
func (s nodeStatus) record(version uint64) string {
	return strconv.FormatUint(version, 10) + "\n" + cmp.Or(javaStates[s.state], "INITIALIZING") + "\n" +
		s.revocation + "\n" + s.loginURL + "\n" + addressList(s.addresses[:]...) + "\n" + s.err + "\n" +
		s.dnsName + "\n" + s.tailnet + "\n" + s.loginName + s.peers
}

// addressList returns the valid addresses, IPv4 first, comma-separated.
func addressList(ips ...netip.Addr) string {
	if !slices.IsSortedFunc(ips, netip.Addr.Compare) {
		ips = slices.Clone(ips)
		slices.SortFunc(ips, netip.Addr.Compare)
	}
	list := make([]byte, 0, 80) // Two IPv6 literals fit on the stack.
	for _, ip := range ips {
		if ip.IsValid() {
			list = append(ip.AppendTo(list), ',')
		}
	}
	return string(list[:max(0, len(list)-1)])
}
