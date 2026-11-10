//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/util/set"
)

// A loopback test control server for tests of the public Java API, which start nodes. With
// interactive login, LoginTest completes it in place of a browser. Otherwise nodes join at once,
// with MagicDNS, and DialTest adds an in-memory peer. No real tailnet is involved.
var loginProbe struct {
	sync.Mutex
	control  *testcontrol.Server
	stop     func()
	received chan int64    // Of the peer's greeting and stalled ports, per connection the dialer closed.
	release  chan struct{} // Closed to let the peer's stalled port read.
	flooded  atomic.Int64  // Bytes the peer's flooding port has written.
	expired  set.Set[key.NodePublic]
	removed  atomic.Bool // Map requests fail as for nodes deleted from the tailnet.
}

// klethron_login_probe_start returns the control URL, or "" on failure.
//
//export klethron_login_probe_start
func klethron_login_probe_start(interactive C.int) *C.char {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control != nil {
		return C.CString("")
	}
	derpMap, stopDERP, err := startLoopbackDERP()
	if err != nil {
		return C.CString("")
	}
	control := &testcontrol.Server{DERPMap: derpMap, Logf: logger.Discard, RequireAuth: interactive != 0}
	if interactive == 0 {
		control.DNSConfig = &tailcfg.DNSConfig{Proxied: true}
		control.MagicDNSDomain = "tail-scale.ts.net" // As in upstream's tsnet tests.
	}
	// Real control answers map requests from deleted nodes with 404, as in upstream's tests.
	control.MaybeRejectRequest = testcontrol.RejectRequestForPath("/machine/map", func() (int, string, string) {
		if loginProbe.removed.Load() {
			return http.StatusNotFound, "", "node not found"
		}
		return 0, "", ""
	})
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Config.ErrorLog = log.New(io.Discard, "", 0)
	control.HTTPTestServer.Start()
	loginProbe.control = control
	loginProbe.stop = func() {
		control.HTTPTestServer.CloseClientConnections()
		control.HTTPTestServer.Close()
		stopDERP()
	}
	return C.CString(control.HTTPTestServer.URL)
}

// klethron_login_probe_start_peer starts an in-memory peer that echoes on port 7070. On port 7071
// it sends a greeting, closes its side, then reports how many bytes it received until the dialer
// closed. On port 7073 it writes floodBytes of the probe pattern, counting progress, then closes.
// On port 7074 it reads nothing until released, then reports how many bytes of the probe pattern
// it received until the dialer closed, or -3 at the first other byte. It returns the peer's
// tailnet IPv4 address, or "" on failure.
//
//export klethron_login_probe_start_peer
func klethron_login_probe_start_peer(path *C.char, length C.int) *C.char {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil || loginProbe.control.RequireAuth || loginProbe.received != nil {
		return C.CString("")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	peer := new(loopbackTailnet)
	stopControl := loginProbe.stop
	loginProbe.stop = func() {
		peer.close()
		stopControl()
	}
	server, ip, problem := peer.startNode(ctx, C.GoStringN(path, length), loginProbe.control.HTTPTestServer.URL, "klethron-peer")
	if problem != "" {
		return C.CString("")
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
			return C.CString("")
		}
		peer.closers = append(peer.closers, func() { _ = ln.Close() })
		go func() {
			for {
				conn, err := ln.Accept()
				if err != nil {
					return
				}
				go func() {
					defer conn.Close()
					handle(conn)
				}()
			}
		}()
	}
	return C.CString(ip.String())
}

// floodBytes exceeds what the dialer and peer can buffer for one connection: netstack's 8 MiB
// receive and 6 MiB send buffers, plus the relay's socket pair.
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

// klethron_login_probe_flooded returns the bytes the peer's flooding port has written.
//
//export klethron_login_probe_flooded
func klethron_login_probe_flooded() C.longlong {
	return C.longlong(loginProbe.flooded.Load())
}

// klethron_login_probe_release lets the peer's stalled port read, once per peer.
//
//export klethron_login_probe_release
func klethron_login_probe_release() {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.release != nil {
		close(loginProbe.release)
		loginProbe.release = nil
	}
}

// klethron_login_probe_await_received returns the count reported by the next greeting or stalled
// connection that the dialer closed, or -2 if none closed within the timeout.
//
//export klethron_login_probe_await_received
func klethron_login_probe_await_received(timeoutMillis C.longlong) C.longlong {
	loginProbe.Lock()
	reports := loginProbe.received
	loginProbe.Unlock()
	if reports == nil {
		return -2
	}
	select {
	case received := <-reports:
		return C.longlong(received)
	case <-time.After(milliseconds(timeoutMillis)):
		return -2
	}
}

// klethron_login_probe_set_peer_online sets whether control reports the in-memory peer as
// online, which it does not by default. It returns whether the peer is registered.
//
//export klethron_login_probe_set_peer_online
func klethron_login_probe_set_peer_online(online C.int) C.int {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil {
		return 0
	}
	for _, node := range loginProbe.control.AllNodes() {
		if node.Hostinfo.Hostname() == "klethron-peer" {
			node.Online = new(online != 0)
			loginProbe.control.UpdateNode(node)
			return 1
		}
	}
	return 0
}

// klethron_login_probe_add_peers registers three unreachable devices: an offline Linux device
// named klethron-other, with addresses 100.101.0.1 and fd7a:115c:a1e0::101:1, a sharee node and
// a Mullvad exit node.
//
//export klethron_login_probe_add_peers
func klethron_login_probe_add_peers() {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil {
		return
	}
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
		loginProbe.control.UpdateNode(node)
	}
	// IPv6 first, which the library reorders.
	add(1, "klethron-other.tail-scale.ts.net.", &tailcfg.Hostinfo{OS: "linux"}, "fd7a:115c:a1e0::101:1", "100.101.0.1")
	add(2, "klethron-sharee.tail-scale.ts.net.", &tailcfg.Hostinfo{OS: "linux", ShareeNode: true}, "100.101.0.2")
	add(3, "us-nyc-wg-301.mullvad.ts.net.", &tailcfg.Hostinfo{OS: "linux"}, "100.101.0.3")
}

// klethron_login_probe_complete acts as the user's browser, approving a login URL. Like real
// control, the approval gives a re-login's new node key a fresh expiry.
//
//export klethron_login_probe_complete
func klethron_login_probe_complete(loginURL *C.char, length C.int) C.int {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil {
		return 0
	}
	// testcontrol stages a re-login's new key as a copy of the expired node.
	for _, node := range loginProbe.control.AllNodes() {
		if !node.KeyExpiry.IsZero() && !loginProbe.expired.Contains(node.Key) {
			node.KeyExpiry = time.Now().Add(24 * time.Hour)
			loginProbe.control.UpdateNode(node)
		}
	}
	if !loginProbe.control.CompleteAuth(C.GoStringN(loginURL, length)) {
		return 0
	}
	return 1
}

// klethron_login_probe_expire expires the node keys of all registered nodes, as when their key
// expiry passes or an administrator expires them. It returns the number of nodes.
//
//export klethron_login_probe_expire
func klethron_login_probe_expire() C.int {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil {
		return 0
	}
	nodes := loginProbe.control.AllNodes()
	loginProbe.expired.Make()
	for _, node := range nodes {
		node.KeyExpiry = time.Now().Add(-time.Minute)
		loginProbe.expired.Add(node.Key)
		loginProbe.control.UpdateNode(node)
	}
	return C.int(len(nodes))
}

// klethron_login_probe_remove makes control answer map requests as for nodes deleted from the
// tailnet, or no longer. A running node notices once it starts a new map request.
//
//export klethron_login_probe_remove
func klethron_login_probe_remove(removed C.int) {
	loginProbe.removed.Store(removed != 0)
}

// klethron_login_probe_reject_registrations makes control reject every registration request,
// including logouts, with an error. Stopping the server would not: nodes keep their hijacked Noise
// connection. testcontrol reads the field without locking; no registration is in flight here.
//
//export klethron_login_probe_reject_registrations
func klethron_login_probe_reject_registrations(reject C.int) {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control != nil {
		loginProbe.control.RequireAuthKey = ""
		if reject != 0 {
			loginProbe.control.RequireAuthKey = "tskey-test-never-sent"
		}
	}
}

// klethron_login_probe_require_machine_auth makes control require an administrator to approve
// new devices. testcontrol reads the field without locking; set it before nodes register.
//
//export klethron_login_probe_require_machine_auth
func klethron_login_probe_require_machine_auth() {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control != nil {
		loginProbe.control.RequireMachineAuth = true
	}
}

// klethron_login_probe_approve_machines acts as a tailnet administrator, approving all registered
// devices. It returns the number approved.
//
//export klethron_login_probe_approve_machines
func klethron_login_probe_approve_machines() C.int {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil {
		return 0
	}
	approved := 0
	url := loginProbe.control.HTTPTestServer.URL
	for _, node := range loginProbe.control.AllNodes() {
		if loginProbe.control.CompleteDeviceApproval(url, url+"/admin", &node.Key) {
			approved++
		}
	}
	return C.int(approved)
}

// klethron_login_probe_hostnames returns the sorted hostnames that registered devices last
// reported to control, separated by newlines.
//
//export klethron_login_probe_hostnames
func klethron_login_probe_hostnames() *C.char {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	var names []string
	if loginProbe.control != nil {
		seen := make(map[tailcfg.StableNodeID]string)
		for _, node := range loginProbe.control.AllNodes() {
			name := node.Hostinfo.Hostname()
			// Key rotation stages two keys for one device; AllNodes includes both.
			// Keep distinct devices and conflicting hostnames visible to assertions.
			if previous, ok := seen[node.StableID]; ok && previous == name {
				continue
			}
			seen[node.StableID] = name
			names = append(names, name)
		}
	}
	slices.Sort(names)
	return C.CString(strings.Join(names, "\n"))
}

//export klethron_login_probe_stop
func klethron_login_probe_stop() {
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
	}
}

// Go copies the process environment when the library loads; Android's Os.setenv cannot change
// what upstream reads afterwards, but Go's own os.Setenv does.
//
//export klethron_login_probe_set_auth_key_environment
func klethron_login_probe_set_auth_key_environment(set C.int) {
	if set != 0 {
		_ = os.Setenv("TS_AUTHKEY", "tskey-test-not-a-key")
	} else {
		_ = os.Unsetenv("TS_AUTHKEY")
	}
}
