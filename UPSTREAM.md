# Upstream issues

Upstream behaviour at the pinned revision (Tailscale `503010831ecd`, Go 1.27.3) that this library
patches, works around or is limited by. Recheck every entry on each upstream update, and drop a patch
or workaround once upstream fixes it. Patches are hash-checked in `BuildGoLibrary.kt` and documented in
`TAILSCALE-MODIFICATIONS`.

## Patched

- **Failed logout destroys the local login.** `controlclient.Direct.TryLogout` clears the in-memory node
  key even when control rejects the logout. The map poll keeps running, and the next netmap saves the
  empty (but `Valid`) `Persist` over the stored key: the device stays registered with no key to log out
  with, a retry is a silent no-op and a restart asks for a new login. Reproduced in about one of three
  runs. Patch: clear the key only after a successful logout (`control/controlclient/direct.go`).
  **To report upstream.**
- **`tsnet.startLogger` creates `tailscaled.log.conf` even with `ts_omit_logtail`.** It still calls
  `logpolicy.ConfigFromFile`/`NewConfig`/`Save`. Patch: return early when logtail is omitted
  (`tsnet/tsnet.go`). Upstream only skips this under `testenv.InTest()`.
- **`ipnlocal.NewLocalBackend` evaluates `logpolicy.LogsDir` even with `ts_omit_logtail`.** The sockstat
  logger is a no-op, but its directory argument runs filesystem discovery that panics on Android. Patch:
  skip it when logtail is omitted (`ipn/ipnlocal/local.go`).

## Worked around

- **Android interface enumeration fails for app UIDs** ([#17311](https://github.com/tailscale/tailscale/issues/17311);
  proposed hook [PR #19455](https://github.com/tailscale/tailscale/pull/19455)). Go's discovery uses
  `RTM_GETLINK`, which SELinux denies to apps (`permission denied`). Workaround: register a bionic
  `getifaddrs` getter via `netmon.RegisterInterfaceGetter`. The `feature/androidbin` fallback
  ([#21129](https://github.com/tailscale/tailscale/issues/21129)) excludes `android && cgo` and its
  synthetic interface cannot describe multi-interface networks.
- **Android netmon cannot see default-network changes.** It polls only every ten minutes and its
  default-route interface is empty without a host report, so change deltas never set
  `RebindLikelyRequired` (no UDP rebind, re-STUN or DERP reconnect). Workaround: public
  `setDefaultNetwork` calls `UpdateLastKnownDefaultRouteInterface`/`UpdateLastKnownDefaultGateway`, then
  `InjectEvent` on each started node's monitor (via the unstable `Server.Sys().NetMon`).
- **Go `crypto/x509` reads the wrong Android CA store** (Go issue, not Tailscale). It reads only
  `/system/etc/security/cacerts`, missing the Conscrypt APEX store used since Android 14 (updated by
  Play system updates), and ignores roots the user disabled in Settings. Workaround: load the APEX (or
  system) store minus `/data/misc/user/<user>/cacerts-removed` and install it with
  `x509.SetFallbackRoots` plus `//go:debug x509usefallbackroots=1`.
- **`tlsdial` falls back to baked-in Let's Encrypt roots** when the platform rejects a chain, bypassing
  user-disabled roots. Workaround: `ts_omit_bakedroots`.
- **`tsnet.Server.Dial` falls back to the device's network.** `tsdial.UserDial` uses netstack only for
  addresses in the network map, then the routing table, then a plain `net.Dialer`. Workaround: resolve
  with `UserDialPlan`, require `UseNetstackForIP`, connect with `NetstackDialTCP`.
- **`tsnet.Server.Dial` starts an unstarted server** with whatever configuration it has, bypassing
  explicit settings. Workaround: `dial` requires a successful public `start`.
- **`tsnet.Server` reads configuration from the process environment** (`TS_CONTROL_URL`,
  `TS_AUTHKEY`/`TS_AUTH_KEY`, OAuth/ID-token variables) whenever a field is empty. Workaround: set every
  field explicitly; refuse to start if an auth-key variable is set; omit `oauthkey`/`useproxy`.
- **Logging defaults.** `Server.logf` defaults to `log.Printf` (which can include login URLs), and
  netmon logs with Go's standard logger directly. Workaround: discard user/backend loggers and call
  `log.SetOutput(io.Discard)` at load.
- **Expired key leaves a running node stuck.** Upstream reports `NeedsLogin`, keeps the old network map
  and never requests a login URL; only `Server.Start` or a user-initiated login does. Workaround: public
  `login()`.
- **Logout resets prefs.** The fresh profile after logout has default prefs (Tailscale's control URL, the
  device hostname); `EditPrefs` would send re-login to the wrong control server, and the control client
  keeps its creation-time URL anyway. Workaround: `login()` uses LocalAPI `Start` with tsnet's prefs.
  **To report upstream.**
- **`LoginFinished` precedes saving the new node key.** `controlclient.Auto` reports `Persist` only with a
  network map. Closing after `LoginFinished` but before that map is processed can lose the completed
  login, even with a clean `Server.Close()`. Confirmed twice with the first map response deliberately
  delayed; this establishes the failure mode, not its frequency in normal use. Embedders that close at
  this boundary can be affected; those that stay running long enough to save the key avoid this loss.
  Workaround: clear the login URL only once a `SelfChange` brings a different node key.
  Tracked by [#19149](https://github.com/tailscale/tailscale/issues/19149); proposed fix
  [PR #19150](https://github.com/tailscale/tailscale/pull/19150) is open and draft. Our tsnet reproduction
  is additional evidence for that issue.
- **`NeedsLogin` is reported shortly before the network map is cleared** after logout, so a status can
  briefly show old addresses. Documented as eventual.
- **`Start` is not context-aware, and concurrent `Start`/`Close` is forbidden.** Workaround: per-node mutex
  in Go; `close` waits at most for local initialization.
- **Netmap cache persists peer data.** With control's `CacheNetworkMaps` capability (requested by
  capability version 149), upstream writes peer names, addresses and keys under `profile-data/`, even for
  ephemeral in-memory nodes. Workaround: `ts_omit_cachenetmap`.

## Limits

- **`ts_omit_acme` breaks the `tsnet` build** (`tsnet.go` calls `local.Client.GetCertificate`
  unconditionally), so `serve`/`acme` stay linked. **To report upstream.**
- `ts_omit_clientmetrics` breaks `util/syspolicy`; `ts_omit_tailnetlock` breaks the debug test servers.
- Test infrastructure: `testcontrol` ignores a logout's expiry (control-side deregistration is untested)
  and copies an expired node on re-login (a probe gives the new key a fresh expiry);
  `httptest.Server.Close` does not close hijacked Noise connections, so stopping it does not make control
  unreachable.
