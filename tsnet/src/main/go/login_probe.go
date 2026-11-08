//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"io"
	"log"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"sync"
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
	control *testcontrol.Server
	stop    func()
	greeted chan int64 // Bytes the peer's greeting port received before the dialer closed.
	expired set.Set[key.NodePublic]
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
// closed. It returns the peer's tailnet IPv4 address, or "" on failure.
//
//export klethron_login_probe_start_peer
func klethron_login_probe_start_peer(path *C.char, length C.int) *C.char {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil || loginProbe.control.RequireAuth || loginProbe.greeted != nil {
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
	echo, err := server.Listen("tcp", ":7070")
	if err != nil {
		return C.CString("")
	}
	peer.closers = append(peer.closers, func() { _ = echo.Close() })
	go serveEcho(echo)
	greet, err := server.Listen("tcp", ":7071")
	if err != nil {
		return C.CString("")
	}
	peer.closers = append(peer.closers, func() { _ = greet.Close() })
	greeted := make(chan int64, 16)
	loginProbe.greeted = greeted
	go func() {
		for {
			conn, err := greet.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = conn.Write([]byte("klethron\n"))
				_ = conn.(interface{ CloseWrite() error }).CloseWrite()
				received, err := io.Copy(io.Discard, conn)
				if err != nil {
					received = -1
				}
				greeted <- received
			}()
		}
	}()
	return C.CString(ip.String())
}

// klethron_login_probe_await_greeted returns the bytes received by the next greeting connection
// that the dialer closed, -1 after a read error, or -2 if none closed within the timeout.
//
//export klethron_login_probe_await_greeted
func klethron_login_probe_await_greeted(timeoutMillis C.longlong) C.longlong {
	loginProbe.Lock()
	greeted := loginProbe.greeted
	loginProbe.Unlock()
	if greeted == nil {
		return -2
	}
	select {
	case received := <-greeted:
		return C.longlong(received)
	case <-time.After(milliseconds(timeoutMillis)):
		return -2
	}
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
		for _, node := range loginProbe.control.AllNodes() {
			names = append(names, node.Hostinfo.Hostname())
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
		loginProbe.stop()
		loginProbe.control = nil
		loginProbe.greeted = nil
		loginProbe.expired = nil
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
