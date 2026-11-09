# Upstream issues

Upstream behaviour at the pinned revision (Tailscale
[`6372b9a43a5c`](https://github.com/tailscale/tailscale/commit/6372b9a43a5cbc3628bf65bd0ec40b4823510d2d), Go 1.27.3) that this library
patches, works around or is limited by. Recheck every entry on each upstream update, and drop a patch
or workaround once upstream fixes it. Patches are hash-checked in `BuildGoLibrary.kt` and documented in
`TAILSCALE-MODIFICATIONS`.

## Patched

- **Failed logout destroys the local login** ([#22248](https://github.com/tailscale/tailscale/issues/22248)). `controlclient.Direct.TryLogout` clears the in-memory node
  key even when control rejects the logout. The map poll keeps running, and the next netmap saves the
  empty (but `Valid`) `Persist` over the stored key: the device stays registered with no key to log out
  with, a retry is a silent no-op and a restart asks for a new login. Reproduced in about one of three
  runs. Patch: clear the key only after a successful logout (`control/controlclient/direct.go`).
- **A netmap in flight can undo a logout** ([#22274](https://github.com/tailscale/tailscale/issues/22274)). `setControlClientStatusLocked` ignores an old control
  client's status only on entry, then unlocks for `tkaSyncIfNeeded` while handling a netmap. A logout
  that resets the profile in that window has the old netmap installed on the logged-out profile:
  `NeedsLogin -> Stopped (nm=true)` with the old addresses, and a re-login then reports `Starting` with
  them. A netmap is often in flight as a node reaches Running. Seen in 4 of 16 Pixel runs; on a host,
  in 5 of 10 runs with that window widened by 300 ms. Patch: re-check the client after relocking
  (`ipn/ipnlocal/local.go`).

## Worked around

- **Android interface enumeration fails for app UIDs** ([#17311](https://github.com/tailscale/tailscale/issues/17311);
  proposed hook [PR #19455](https://github.com/tailscale/tailscale/pull/19455)). Go's discovery uses
  `RTM_GETLINK`, which SELinux denies to apps. Workaround: register a bionic `getifaddrs` getter via
  `netmon.RegisterInterfaceGetter`. The `feature/androidbin` fallback
  ([#21129](https://github.com/tailscale/tailscale/issues/21129)) excludes `android && cgo` and its
  synthetic interface cannot describe multi-interface networks.
- **Android netmon cannot see default-network changes.** It polls only every ten minutes and its
  default-route interface is empty without a host report, so change deltas never set
  `RebindLikelyRequired` (no UDP rebind, re-STUN or DERP reconnect). Workaround: public
  `setDefaultNetwork` calls `UpdateLastKnownDefaultRouteInterface`/`UpdateLastKnownDefaultGateway`, then
  `InjectEvent` on each started node's monitor (via the unstable `Server.Sys().NetMon`).
- **Go `crypto/x509` reads the wrong Android CA store** (a Go issue). It reads only
  `/system/etc/security/cacerts`, missing the Conscrypt APEX store used since Android 14 (updated by
  Play system updates), and ignores roots the user disabled. Workaround: load the APEX (or system)
  store minus `/data/misc/user/<user>/cacerts-removed` and install it with `x509.SetFallbackRoots` plus
  `//go:debug x509usefallbackroots=1`.
- **`tlsdial` falls back to baked-in Let's Encrypt roots** when the platform rejects a chain, bypassing
  user-disabled roots. Workaround: `ts_omit_bakedroots`.
- **`tsnet.Server.Dial` falls back to the device's network.** `tsdial.UserDial` uses netstack only for
  addresses in the network map, then the routing table, then a plain `net.Dialer`. Workaround: resolve
  with `UserDialPlan`, require `UseNetstackForIP`, connect with `NetstackDialTCP`.
- **`tsnet.Server.Dial` starts an unstarted server** with whatever configuration it has. Workaround:
  `dial` requires a successful public `start`.
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
  [PR #21857](https://github.com/tailscale/tailscale/pull/21857) adds login orchestration, but its
  helper is private and not yet exposed through LocalAPI.
- **`LoginFinished` precedes saving the new node key.** `controlclient.Auto` reports `Persist` only with a
  network map, so closing between `LoginFinished` and that map can lose the completed login, even with a
  clean `Server.Close()` (confirmed twice with the first map response deliberately delayed). Workaround:
  clear the login URL only once a `SelfChange` brings a different node key. Tracked by
  [#19149](https://github.com/tailscale/tailscale/issues/19149); draft fix
  [PR #19150](https://github.com/tailscale/tailscale/pull/19150).
- **Revocations are indistinguishable from logout on the IPN bus.** Upstream reports `NeedsLogin` after a
  logout, key expiry and device removal alike. Workaround, as upstream's state machine: removal while the
  `node-removed` health warning is set (read from the health tracker, because its bus notification can
  follow the state), otherwise expiry while the latest `SelfChange` key expiry has passed (reset at
  `NoState`, which starts a new profile, as after logout).
- **A watch that falls behind ends with an `ErrMessage`** that is not a backend error. Workaround: drop
  that exact text (unexported `watchIPNBusFellBehindMessage`) before reporting `error()`.
- **`Start` is not context-aware, and concurrent `Start`/`Close` is forbidden.** Workaround: per-node mutex
  in Go; `close` waits at most for local initialization.
- **Netmap cache persists peer data.** With control's `CacheNetworkMaps` capability, upstream writes peer
  names, addresses and keys under `profile-data/`, even for ephemeral in-memory nodes. Workaround:
  `ts_omit_cachenetmap`.
- **Subprocess call sites are linked.** Tailscale packages in the release graph have 21 `os/exec` call
  sites. All but one are unreachable here by build tag, `runtime.GOOS` (Android is not `linux`), an
  omitted feature, a pref we never set, or a real TUN (tsnet uses a fake one). The exception: netstack
  relays ICMP echo requests to subnet or 4via6 addresses by running `/system/bin/ping` (`ProcessSubnets`,
  which tsnet enables), reachable only if the packet filter accepts such a destination. Workaround:
  `ts_omit_advertiseroutes` and `ts_omit_appconnectors`, so the filter accepts only the node's own and
  service addresses. Re-audit with `go list -deps` on each update.

## Limits

- Live-tailnet coverage is still needed for 16 KiB devices, DERP-to-direct upgrades, restrictive NATs,
  subnet routes, IPv6 peers, throughput, prolonged outages, scheduled key expiry, revocation while
  stopped and Play split delivery.

- **`ts_omit_serve` breaks the `tsnet` build** (`tsnet.go` calls `SetServeConfig`/`GetServeConfig`
  unconditionally), so serve stays linked. **To report upstream.**
- `ts_omit_tailnetlock` breaks the debug test servers.
- Test infrastructure: `testcontrol` ignores a logout's expiry (control-side deregistration is untested)
  and copies an expired node on re-login (a probe gives the new key a fresh expiry). It answers unknown
  nodes' map requests with 400, not control's 404, and keeps streaming to running nodes, so removal is
  simulated with a 404 for map requests that a node sees at its next start. `httptest.Server.Close` does
  not close hijacked Noise connections, so stopping it does not make control unreachable.
  `AllNodes` includes staged rotation keys, including abandoned candidates; the hostname probe counts
  devices by stable ID.

## Resolved upstream

- [PR #22253](https://github.com/tailscale/tailscale/pull/22253) fixes `ts_omit_clientmetrics` builds
  through `util/syspolicy`. Both variants now omit client metrics.

## Updating upstream

Resolve `tailscale/tailscale` main to a full SHA; update the Go requirement and sums, the version
assertions (`TailnetNodeTest`, `MinifiedDemoTest`) and the documented SHA; re-check patch hashes and every
entry above. Re-audit initialization, filesystem and subprocess reachability and state permissions, and
retest the state format. Update both notice inventories (THIRD-PARTY-NOTICES.md), then rebuild both ABIs
and repeat the device tests. Omit `serve` once upstream fixes its build; when the omitted feature set
changes, revalidate it on a real tailnet with the user.
