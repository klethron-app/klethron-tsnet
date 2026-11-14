//go:build klethron_test

// Copied into the pinned backend package only for Android instrumentation.
package ipnlocal

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"

	"tailscale.com/control/controlclient"
	"tailscale.com/ipn"
	"tailscale.com/net/netmon"
	"tailscale.com/net/tsdial"
	"tailscale.com/tailcfg"
	"tailscale.com/tka"
	"tailscale.com/tstest/tkatest"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/types/netmap"
	"tailscale.com/types/persist"
	"tailscale.com/util/eventbus"
)

type klethronTKATransport func(*http.Request) (*http.Response, error)

func (f klethronTKATransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// KlethronProbeTailnetLockSync delivers a successful RPC after the backend has
// changed profiles or logged out, during the RPC's unlocked interval. The probe
// uses real JNI storage callbacks and the production response decoding/sync code.
func KlethronProbeTailnetLockSync(store ipn.StateStore, mode string) (problem string) {
	defer func() {
		if recover() != nil {
			problem = "late Tailnet Lock response panicked"
		}
	}()
	phase, change, _ := strings.Cut(mode, "/")
	if phase != "bootstrap" && phase != "offer" && phase != "send" && phase != "queued" {
		return "unknown sync phase"
	}
	const oldProfile ipn.ProfileID = "probe-race"
	var nextProfile ipn.ProfileID
	if change == "profile" {
		nextProfile = "probe-race-next"
	} else if change != "logout" && change != "key" {
		return "unknown profile change"
	}
	persistence := store.(interface {
		OpenTailnetLock(ipn.ProfileID) (*tka.Authority, tka.CompactableChonk, error)
		DeleteTailnetLock(ipn.ProfileID) error
	})
	defer persistence.DeleteTailnetLock(oldProfile)
	defer persistence.DeleteTailnetLock(nextProfile)
	signer := key.NewTLPrivate()
	controlStorage := tka.ChonkMem()
	control, genesis, err := tka.Create(controlStorage, tka.State{
		Keys:              []tka.Key{{Kind: tka.Key25519, Public: signer.Public().Verifier(), Votes: 1}},
		DisablementValues: [][]byte{tka.DisablementKDF([]byte("test"))},
	}, signer)
	if err != nil {
		return "create authority failed"
	}
	b := &LocalBackend{
		store: store, logf: logger.Discard, capTailnetLock: true,
		pm: &profileManager{currentProfile: (&ipn.LoginProfile{ID: oldProfile}).View()},
	}
	if phase == "offer" || phase == "send" {
		_, storage, err := persistence.OpenTailnetLock(oldProfile)
		if err != nil {
			return "open authority failed"
		}
		authority, err := tka.Bootstrap(storage, genesis)
		if err != nil {
			return "bootstrap authority failed"
		}
		b.tka = &tkaState{profile: oldProfile, authority: authority, storage: storage}
	}
	// A newer signed control update ensures the offer proceeds through sync/send.
	updater := control.NewUpdater(signer)
	if updater.SetKeyMeta(control.Keys()[0].MustID(), map[string]string{"revision": "next"}) != nil {
		return "prepare authority update failed"
	}
	updates, err := updater.Finalize(controlStorage)
	if err != nil || control.Inform(controlStorage, updates) != nil {
		return "commit authority update failed"
	}
	changed, requested := false, false
	client := &http.Client{Transport: klethronTKATransport(func(r *http.Request) (*http.Response, error) {
		defer r.Body.Close()
		requested = true
		if phase == "queued" {
			return nil, errors.New("queued sync issued a request")
		}
		response := httptest.NewRecorder()
		switch r.URL.Path {
		case "/machine/tka/bootstrap":
			err = json.NewEncoder(response).Encode(tailcfg.TKABootstrapResponse{GenesisAUM: genesis.Serialize()})
		case "/machine/tka/sync/offer":
			err = tkatest.HandleTKASyncOffer(response, r, control, controlStorage)
		case "/machine/tka/sync/send":
			err = tkatest.HandleTKASyncSend(response, r, control, controlStorage)
		default:
			return nil, errors.New("unexpected sync request")
		}
		if err != nil {
			return nil, err
		}
		if strings.HasSuffix(r.URL.Path, "/"+phase) {
			// These are the fields reset by logout/profile changes while the RPC
			// releases b.mu. Keep the same client for implicit profile changes.
			b.mu.Lock()
			if change == "logout" {
				b.cc = nil
				b.pm.prefs = ipn.NewPrefs().View()
			}
			if change == "key" {
				b.pm.prefs = (&ipn.Prefs{Persist: &persist.Persist{PrivateNodeKey: key.NewNode()}}).View()
			} else {
				b.tka = nil
				b.pm.currentProfile = (&ipn.LoginProfile{ID: nextProfile}).View()
				err = persistence.DeleteTailnetLock(oldProfile)
			}
			b.mu.Unlock()
			changed = true
		}
		return response.Result(), err
	})}
	bus := eventbus.New()
	defer bus.Close()
	monitor := netmon.NewStatic()
	defer monitor.Close()
	dialer := tsdial.NewDialer(monitor)
	defer dialer.Close()
	cc, err := controlclient.New(controlclient.Options{
		ServerURL: "https://control.invalid", Hostinfo: &tailcfg.Hostinfo{Hostname: "probe"},
		GetMachinePrivateKey: func() (key.MachinePrivate, error) { return key.NewMachine(), nil },
		NoiseTestClient:      client, HTTPTestClient: client, Dialer: dialer, Bus: bus,
		Logf: logger.Discard, StartPaused: true,
	})
	if err != nil {
		return "create control client failed"
	}
	defer cc.Shutdown()
	b.cc, b.ccAuto = cc, cc
	prefs := (&ipn.Prefs{Persist: &persist.Persist{PrivateNodeKey: key.NewNode()}}).View()
	b.pm.prefs = prefs
	if phase == "queued" {
		// A map captured by the old caller can wait on tkaSyncLock while the
		// client or credentials change. Reject it before issuing any request.
		if change == "logout" {
			b.cc = nil
		} else {
			b.pm.prefs = (&ipn.Prefs{Persist: &persist.Persist{PrivateNodeKey: key.NewNode()}}).View()
		}
		changed = true
	}
	err = b.tkaSyncIfNeeded(cc, &netmap.NetworkMap{TKAEnabled: true, TKAHead: control.Head()}, prefs)
	if phase == "queued" && requested {
		return "queued sync issued a request"
	}
	if !changed {
		return "sync did not reach held response"
	}
	if err == nil || b.tka != nil {
		return "late response restored authority"
	}
	for _, profile := range []ipn.ProfileID{oldProfile, nextProfile} {
		if authority, _, err := persistence.OpenTailnetLock(profile); err != nil || authority != nil {
			return "late response persisted authority"
		}
	}
	return "ok"
}
