//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"io"
	"log"
	"net/http/httptest"
	"os"
	"sync"

	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

// A loopback test control server requiring interactive login, which LoginTest completes in place
// of a browser. Nodes are started through the public Java API. No real tailnet is involved.
var loginProbe struct {
	sync.Mutex
	control *testcontrol.Server
	stop    func()
}

// klethron_login_probe_start returns the control URL, or "" on failure.
//
//export klethron_login_probe_start
func klethron_login_probe_start() *C.char {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control != nil {
		return C.CString("")
	}
	derpMap, stopDERP, err := startLoopbackDERP()
	if err != nil {
		return C.CString("")
	}
	control := &testcontrol.Server{DERPMap: derpMap, Logf: logger.Discard, RequireAuth: true}
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

// klethron_login_probe_complete acts as the user's browser, approving a login URL.
//
//export klethron_login_probe_complete
func klethron_login_probe_complete(loginURL *C.char, length C.int) C.int {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control == nil || !loginProbe.control.CompleteAuth(C.GoStringN(loginURL, length)) {
		return 0
	}
	return 1
}

//export klethron_login_probe_stop
func klethron_login_probe_stop() {
	loginProbe.Lock()
	defer loginProbe.Unlock()
	if loginProbe.control != nil {
		loginProbe.stop()
		loginProbe.control = nil
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
