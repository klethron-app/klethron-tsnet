//go:build klethron_test

package main

import (
	"context"
	"fmt"
	"io"
	"maps"
	"net"
	"net/http"
	"net/netip"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/tailcfg"
	"tailscale.com/tailcfg/nodecap"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/util/set"
)

// Loopback control for Java API tests. LoginTest approves interactive logins; other nodes join
// automatically with MagicDNS. DialTest adds an in-memory peer.
var loginProbe struct {
	sync.Mutex
	control  *testcontrol.Server
	stop     func()
	received chan int64    // Of the peer's greeting and stalled ports, per connection the dialer closed.
	release  chan struct{} // Closed to let the peer's stalled port read.
	flooded  atomic.Int64  // Bytes the peer's flooding port has written.
	expired  set.Set[key.NodePublic]
	removed  atomic.Bool // Map requests fail as for nodes deleted from the tailnet.
	rejected atomic.Bool // Registration requests fail, including logout.
}

// startControl starts the control server, requiring interactive login if interactive is "true".
// It returns the control URL, or "" on failure.
func startControl(interactive string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	control := &testcontrol.Server{RequireAuth: interactive == "true"}
	if interactive == "locked" {
		lockExpectSaved.Store(false)
		lockMissingHead.Store(0)
		control.ModifyFirstMapResponse = func(response *tailcfg.MapResponse, request *tailcfg.MapRequest) {
			if response.Node.CapMap == nil {
				response.Node.CapMap = make(tailcfg.NodeCapMap)
			}
			response.Node.CapMap[nodecap.TailnetLock] = nil
			control.SetNodeCapMap(request.NodeKey, response.Node.CapMap)
			if lockExpectSaved.Load() && request.TKAHead == "" {
				lockMissingHead.Add(1)
			}
		}
	}
	if !control.RequireAuth {
		control.DNSConfig = &tailcfg.DNSConfig{Proxied: true}
		control.MagicDNSDomain = "tail-scale.ts.net" // As in upstream's tsnet tests.
	}
	// Real control answers map requests from deleted nodes with 404, as in upstream's tests.
	control.MaybeRejectRequest = func(request *http.Request) (int, string, string) {
		if request.URL.Path == "/machine/map" && loginProbe.removed.Load() {
			return http.StatusNotFound, "", "node not found"
		}
		if request.URL.Path == "/machine/register" && loginProbe.rejected.Load() {
			// Registration errors use a JSON response with HTTP 200, as in testcontrol.
			return http.StatusOK, "", `{"Error":"invalid authkey"}`
		}
		return 0, "", ""
	}
	stop, err := startLoopbackControl(control)
	if err != nil {
		return ""
	}
	loginProbe.control, loginProbe.stop = control, stop
	return control.HTTPTestServer.URL
}

// Check the retired key: cancelled registrations can leave unrelated nodes in testcontrol.
func probeNonInteractiveRotation(string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	control := new(testcontrol.Server)
	stop, err := startLoopbackControl(control)
	if err != nil {
		return "control failed"
	}
	defer stop()
	fixture := new(loopbackTailnet)
	defer fixture.close()
	server, _, problem := fixture.startNode(ctx, control.HTTPTestServer.URL, "rotation")
	if problem != "" {
		return problem
	}
	previous := server.Status().Self.PublicKey
	client, err := server.LocalClient()
	if err != nil || client.StartLoginInteractive(ctx) != nil {
		return "login failed"
	}
	for ctx.Err() == nil {
		if server.Status().Self.PublicKey != previous && control.Node(previous) == nil {
			return "ok"
		}
		time.Sleep(10 * time.Millisecond)
	}
	return "rotation did not retire the previous node key"
}

// startPeer returns an in-memory peer's IPv4 address. Ports: 7070 echoes; 7071 greets and half-closes;
// 7073 floods; 7074 waits for release. The latter two use the probe pattern; 7071/7074 report bytes
// received until close. Mode "dns" also starts split DNS.
func startPeer(mode string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	peer := new(loopbackTailnet)
	stop := loginProbe.stop
	loginProbe.stop = func() {
		peer.close()
		stop()
	}
	server, ip, problem := peer.startNode(ctx, loginProbe.control.HTTPTestServer.URL, "klethron-peer")
	if problem != "" {
		return ""
	}
	received := make(chan int64, 16)
	release := make(chan struct{})
	loginProbe.received, loginProbe.release = received, release
	loginProbe.flooded.Store(0)
	handlers := map[string]func(net.Conn){
		":7070": func(conn net.Conn) { _, _ = io.Copy(conn, conn) },
		":7071": func(conn net.Conn) {
			_, _ = conn.Write([]byte("klethron\n"))
			_ = conn.(interface{ CloseWrite() error }).CloseWrite()
			received <- countReceived(conn, false)
		},
		":7073": func(conn net.Conn) {
			chunk := make([]byte, 16<<10)
			for written := 0; written < floodBytes; written += len(chunk) {
				for i := range chunk {
					chunk[i] = byte((written + i) % 251)
				}
				if _, err := conn.Write(chunk); err != nil {
					return
				}
				loginProbe.flooded.Add(int64(len(chunk)))
			}
		},
		":7074": func(conn net.Conn) {
			<-release
			received <- countReceived(conn, true)
		},
	}
	for port, handle := range handlers {
		ln, err := server.Listen("tcp", port)
		if err != nil {
			return ""
		}
		peer.closers = append(peer.closers, func() { _ = ln.Close() })
		go serve(ln, handle)
	}
	if mode == "dns" {
		if err := startPeerDNS(peer, server, ip, loginProbe.control); err != nil {
			return "DNS peer failed"
		}
	}
	return ip.String()
}

// floodBytes exceeds what the dialer and peer can buffer for one connection: netstack's 8 MiB
// receive and 6 MiB send buffers, plus the relayed socket pair's.
const floodBytes = 48 << 20

// countReceived reads conn to its end, returning the bytes read, -1 after a read error, or with
// pattern, -3 at the first byte that differs from the probe pattern.
func countReceived(conn net.Conn, pattern bool) int64 {
	buffer := make([]byte, 64<<10)
	var total int64
	for {
		n, err := conn.Read(buffer)
		for i, b := range buffer[:n] {
			if pattern && b != byte((total+int64(i))%251) {
				return -3
			}
		}
		total += int64(n)
		if err == io.EOF {
			return total
		}
		if err != nil {
			return -1
		}
	}
}

func flooded(string) string {
	return strconv.FormatInt(loginProbe.flooded.Load(), 10)
}

// releaseStalled lets the peer's stalled port read, once per peer.
func releaseStalled(string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.release != nil {
		close(loginProbe.release)
		loginProbe.release = nil
	}
	return ""
}

// awaitReceived returns the count reported by the next greeting or stalled connection that the
// dialer closed, or -2 if none closed within 30 seconds.
func awaitReceived(string) string {
	loginProbe.Lock()
	reports := loginProbe.received
	loginProbe.Unlock()
	select {
	case received := <-reports:
		return strconv.FormatInt(received, 10)
	case <-time.After(30 * time.Second):
		return "-2"
	}
}

// withControl holds the probe lock and requires a started control server.
func withControl(probe func(control *testcontrol.Server, argument string) string) func(string) string {
	return func(argument string) string {
		loginProbe.Lock()
		defer loginProbe.Unlock()
		if loginProbe.control == nil {
			return "not started"
		}
		return probe(loginProbe.control, argument)
	}
}

// setPeerOnline sends the other nodes a patch marking the in-memory peer online, as control does
// for presence changes, without storing it. It returns whether the peer is registered.
func setPeerOnline(control *testcontrol.Server, _ string) string {
	all := control.AllNodes()
	i := slices.IndexFunc(all, func(node *tailcfg.Node) bool { return node.Hostinfo.Hostname() == "klethron-peer" })
	if i < 0 {
		return "false"
	}
	patch := &tailcfg.MapResponse{PeersChangedPatch: []*tailcfg.PeerChange{{NodeID: all[i].ID, Online: new(true)}}}
	for _, node := range slices.Delete(all, i, i+1) {
		control.AddRawMapResponse(node.Key, patch)
	}
	return "true"
}

// setLoginName sends profile-only updates, without a self or peer change to trigger a status read.
// AddRawMapResponse also suppresses later automatic full maps, keeping the update isolated.
// Returns the number of connected nodes that received an update.
func setLoginName(control *testcontrol.Server, name string) string {
	updated := 0
	for _, node := range control.AllNodes() {
		response := &tailcfg.MapResponse{UserProfiles: []tailcfg.UserProfile{{ID: node.User, LoginName: name}}}
		if control.AddRawMapResponse(node.Key, response) {
			updated++
		}
	}
	return strconv.Itoa(updated)
}

// Register an offline peer with valid/invalid SSH keys, a sharee and a Mullvad exit node.
func addPeers(control *testcontrol.Server, _ string) string {
	add := func(id int, name string, hostinfo *tailcfg.Hostinfo, addresses ...string) {
		node := &tailcfg.Node{
			ID: tailcfg.NodeID(1000 + id), StableID: tailcfg.StableNodeID(fmt.Sprintf("KLETHRON%d", id)),
			Name: name, Key: key.NewNode().Public(), Machine: key.NewMachine().Public(),
			DiscoKey: key.NewDisco().Public(), MachineAuthorized: true, Hostinfo: hostinfo.View(),
		}
		for _, address := range addresses {
			ip := netip.MustParseAddr(address)
			prefix := netip.PrefixFrom(ip, ip.BitLen())
			node.Addresses = append(node.Addresses, prefix)
			node.AllowedIPs = append(node.AllowedIPs, prefix)
		}
		control.UpdateNode(node)
	}
	// IPv6 first, which the library reorders. It passes host keys through without parsing them.
	other := &tailcfg.Hostinfo{OS: "linux", SSH_HostKeys: []string{
		"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\n", "ssh-rsa AAAA,invalid"}}
	add(1, "klethron-other.tail-scale.ts.net.", other, "fd7a:115c:a1e0::101:1", "100.101.0.1")
	add(2, "klethron-sharee.tail-scale.ts.net.", &tailcfg.Hostinfo{OS: "linux", ShareeNode: true}, "100.101.0.2")
	add(3, "us-nyc-wg-301.mullvad.ts.net.", &tailcfg.Hostinfo{OS: "linux"}, "100.101.0.3")
	return ""
}

// completeLogin acts as the user's browser, approving a login URL, and returns "ok". Like real
// control, the approval gives a re-login's new node key a fresh expiry.
func completeLogin(control *testcontrol.Server, url string) string {
	// testcontrol stages a re-login's new key as a copy of the expired node.
	for _, node := range control.AllNodes() {
		if !node.KeyExpiry.IsZero() && !loginProbe.expired.Contains(node.Key) {
			node.KeyExpiry = time.Now().Add(24 * time.Hour)
			control.UpdateNode(node)
		}
	}
	if !control.CompleteAuth(url) {
		return "not completed"
	}
	return "ok"
}

// Expire every node key and count distinct devices, as approveMachines does.
func expireNodes(control *testcontrol.Server, _ string) string {
	loginProbe.expired.Make()
	devices := make(set.Set[key.MachinePublic])
	for _, node := range control.AllNodes() {
		node.KeyExpiry = time.Now().Add(-time.Minute)
		loginProbe.expired.Add(node.Key)
		devices.Add(node.Machine)
		control.UpdateNode(node)
	}
	return strconv.Itoa(len(devices))
}

func renameNodes(control *testcontrol.Server, name string) string {
	for _, node := range control.AllNodes() {
		node.Name = name
		control.UpdateNode(node)
	}
	return ""
}

// Running nodes notice removal on their next map request.
func removeNodes(removed string) string {
	loginProbe.removed.Store(removed == "true")
	return ""
}

// Reject registration and logout. Stopping the server would leave hijacked Noise connections alive.
func rejectRegistrations(reject string) string {
	loginProbe.rejected.Store(reject == "true")
	return ""
}

// requireMachineAuth makes control require an administrator to approve new devices. testcontrol
// reads the field without locking; set it before nodes register.
func requireMachineAuth(control *testcontrol.Server, _ string) string {
	control.RequireMachineAuth = true
	return ""
}

// Approve and count distinct devices. testcontrol can retain abandoned or staged node keys for
// one device; their shared machine key identifies it.
func approveMachines(control *testcontrol.Server, _ string) string {
	approved := make(set.Set[key.MachinePublic])
	url := control.HTTPTestServer.URL
	for _, node := range control.AllNodes() {
		if control.CompleteDeviceApproval(url, url+"/admin", &node.Key) {
			approved.Add(node.Machine)
		}
	}
	return strconv.Itoa(len(approved))
}

// Sorted hostnames, deduplicated by device while retaining conflicting names for assertions.
func hostnames(control *testcontrol.Server, _ string) string {
	names := make(map[string]string) // By machine key and hostname.
	for _, node := range control.AllNodes() {
		names[node.Machine.String()+" "+node.Hostinfo.Hostname()] = node.Hostinfo.Hostname()
	}
	return strings.Join(slices.Sorted(maps.Values(names)), "\n")
}

func stopControl(string) string {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control != nil {
		if loginProbe.release != nil {
			close(loginProbe.release) // Ends stalled handlers once their connections close.
		}
		loginProbe.stop()
		loginProbe.control = nil
		loginProbe.received, loginProbe.release = nil, nil
		loginProbe.expired = nil
		loginProbe.removed.Store(false)
		loginProbe.rejected.Store(false)
	}
	return ""
}

// setAuthKeyEnvironment sets TS_AUTHKEY if enabled is "true", otherwise unsets it. Go copies the
// process environment when the library loads; Android's Os.setenv cannot change what upstream
// reads afterwards, but Go's own os.Setenv does.
func setAuthKeyEnvironment(enabled string) string {
	if enabled == "true" {
		_ = os.Setenv("TS_AUTHKEY", "tskey-test-not-a-key")
	} else {
		_ = os.Unsetenv("TS_AUTHKEY")
	}
	return ""
}
