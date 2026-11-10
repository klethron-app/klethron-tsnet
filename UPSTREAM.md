# Upstream issues

Upstream issues at Tailscale
[`5b6c39b94d50`](https://github.com/tailscale/tailscale/commit/5b6c39b94d50beac39dd7302d6f75f14e281a01f)
with Go 1.27.4. Recheck each entry on every update and drop fixes upstream has made.
`BuildGoLibrary.kt` hash-checks the patch; `TAILSCALE-MODIFICATIONS` documents it.

## Patched

- **Failed logout destroys the local login** ([#22248](https://github.com/tailscale/tailscale/issues/22248)). `controlclient.Direct.TryLogout` clears the in-memory node
  key even when control rejects the logout. The map poll keeps running, and the next netmap saves the
  empty (but `Valid`) `Persist` over the stored key: the device stays registered with no key to log out
  with, a retry is a silent no-op and a restart asks for a new login. Reproduced in about one of three
  runs. Patch: clear the key only after a successful logout (`control/controlclient/direct.go`).

## Worked around

- **Android interface enumeration fails for app UIDs** ([#17311](https://github.com/tailscale/tailscale/issues/17311);
  proposed hook [PR #19455](https://github.com/tailscale/tailscale/pull/19455)). Go's discovery uses
  `RTM_GETLINK`, which SELinux denies to apps. Workaround: register a bionic `getifaddrs` getter via
  `netmon.RegisterInterfaceGetter`. The `feature/androidbin` fallback
  ([#21129](https://github.com/tailscale/tailscale/issues/21129)) excludes `android && cgo` and its
  synthetic interface cannot describe multi-interface networks.
- **Android netmon cannot see default-network changes.** It polls only every ten minutes and its
  default-route interface is empty without a host report, so change deltas never set
  `RebindLikelyRequired` (no UDP rebind, re-STUN or DERP reconnect). Workaround: while nodes run,
  `TailnetNode` follows the best `INTERNET`, `NOT_VPN` network with `registerBestMatchingNetworkCallback`
  and calls `UpdateLastKnownDefaultRouteInterface`/`UpdateLastKnownDefaultGateway`, then `InjectEvent`
  on each started node's monitor (via the unstable `Server.Sys().NetMon`). Without
  `CHANGE_NETWORK_STATE`, Android adds `FOREGROUND` to the request and does not rematch it when
  background cellular becomes foreground: it got only `onLost` after Wi-Fi loss in 10 of 10 runs on a
  Pixel 8 Pro and the API 37.2 emulator (10 of 10 passed with the permission). The library declares it
  and falls back to `registerDefaultNetworkCallback` if an app removes it.
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
  `TailnetNode.start` returns only started nodes.
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
  keeps its creation-time URL anyway. Workaround: `login()` uses LocalAPI `Start` with tsnet's prefs
  when the prefs differ or say logged out. `Start` always restarts the control client through `NoState`,
  so renewing a running node's login calls only `StartLoginInteractive`.
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
- **`Start` is not context-aware, and concurrent `Start`/`Close` is forbidden.** LocalAPI `Start` also
  ignores its context and, run after `Close`, creates a control client that retries forever.
  Workaround: `TailnetNode.start` returns a node only once started, and a per-node read-write lock
  lets `close` cancel calls, then wait for them; `login` waits for LocalAPI `Start` even when
  cancelled.
- **Netmap cache persists peer data.** With control's `CacheNetworkMaps` capability, upstream writes peer
  names, addresses and keys under `profile-data/`, even for ephemeral in-memory nodes. Workaround:
  `ts_omit_cachenetmap`.
- **Peer status hides fewer peers than the CLI suggests.** `tailscale status` hides Mullvad exit nodes
  only when `ExitNodeOption` is set, which `ts_omit_useexitnode` leaves false. Workaround: hide peers
  named `*.mullvad.ts.net` (and sharee nodes, as the CLI does). IPN bus peer deltas do not report the
  network map clearing on logout. Workaround: read `Status` after each state or peer notification.
- **Subprocess call sites are linked.** Tailscale packages in the release graph have 21 `os/exec` call
  sites. All but one are unreachable here by build tag, `runtime.GOOS` (Android is not `linux`), an
  omitted feature, a pref we never set, or a real TUN (tsnet uses a fake one). The exception: netstack
  relays ICMP echo requests to subnet or 4via6 addresses by running `/system/bin/ping` (`ProcessSubnets`,
  which tsnet enables), reachable only if the packet filter accepts such a destination. Workaround:
  `ts_omit_advertiseroutes` and `ts_omit_appconnectors`, so the filter accepts only the node's own and
  service addresses. Re-audit with `go list -deps` on each update.

## Limits

- **Another app's VPN.** Its network would be the default and hide Wi-Fi and cellular changes: a
  control connection dialled over lingering cellular then hung until the two-minute long-poll timeout
  (Pixel 11 Pro XL with the Tailscale app). Apps cannot read a VPN's underlying networks, so nodes
  follow the best non-VPN network, which split tunnels such as Tailscale's leave their traffic on. With
  a full-tunnel VPN, traffic uses the VPN while nodes follow the network below it; untested.
- Live-tailnet coverage is still needed for default-network changes, 16 KiB devices, DERP-to-direct
  upgrades, restrictive NATs, subnet routes, IPv6 peers, throughput, prolonged outages, scheduled key
  expiry, revocation while stopped and Play split delivery.
- **Names without a tailnet DNS route** fall back to the device's resolver when tailnet DNS fails
  (`tsnet` `dnsResolver.hostLookup`), so the name leaves the tailnet; `dial` still connects only to
  tailnet addresses. Upstream has no option to disable the fallback.
- `ts_omit_tailnetlock` breaks the debug test servers.
- Test infrastructure: `testcontrol` ignores a logout's expiry (control-side deregistration is untested)
  and copies an expired node on re-login (a probe gives the new key a fresh expiry). It answers unknown
  nodes' map requests with 400, not control's 404, and keeps streaming to running nodes, so removal is
  simulated with a 404 for map requests that a node sees at its next start. `httptest.Server.Close` does
  not close hijacked Noise connections, so stopping it does not make control unreachable.
  `AllNodes` lists every key that started registering, including staged rotation keys and a new node's
  first key, abandoned when `Start`'s interactive login begins first; the probes count devices by
  machine key.

## Updating upstream

Resolve `tailscale/tailscale` main to a full SHA; update the Go requirement and sums, the version
assertion in `TailnetNodeTest` and the documented SHA; re-check patch hashes and every
entry above. Re-audit initialization, filesystem and subprocess reachability and state permissions, and
retest the state format. Update both notice inventories (THIRD-PARTY-NOTICES.md), then rebuild both ABIs
and repeat the device tests. When the omitted feature set changes, revalidate it on a real tailnet with
the user.
