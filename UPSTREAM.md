# Upstream issues

Pinned: Tailscale [`5b6c39b94d50`](https://github.com/tailscale/tailscale/commit/5b6c39b94d50beac39dd7302d6f75f14e281a01f)
with Go 1.27.4. Entries are ordered by impact on this library; drop those upstream has fixed.

## Patched

- **A failed logout cannot be retried** ([#22248](https://github.com/tailscale/tailscale/issues/22248);
  fix [PR #22249](https://github.com/tailscale/tailscale/pull/22249)). `controlclient.Direct.TryLogout`
  forgets the in-memory node key even when control rejects the logout, so retries fail until the node
  restarts. Patch: forget the key only after a successful logout.
  `BuildGoLibrary.kt` applies it to a hash-checked copy of `control/controlclient/direct.go`;
  `TAILSCALE-MODIFICATIONS` documents it. `LoginTest.failedLogoutKeepsTheLoginForRetries` fails without it.
- **Names outside the tailnet's DNS go to the device's resolver.** When no tailnet DNS route covers a
  name and its tailnet queries fail, `tsnet` `dnsResolver.lookupCandidate` asks `net.DefaultResolver`,
  which sends the name to the device's network and lets its DNS answer with any address, including a
  reachable peer's. Upstream has no option to disable this. Patch: count that case as a failed lookup,
  in a hash-checked copy of `tsnet/dns_lookup.go`. `DialTest.reachesOnlyTheTailnet` fails without it.

## Worked around

- **Logs can contain login URLs.** `tsnet.Server.logf` defaults to `log.Printf`, and netmon and others
  use Go's standard logger directly. `main.go` sets `UserLogf` and `Logf` to `logger.Discard` and
  calls `log.SetOutput(io.Discard)`.
- **`tsnet.Server` reads empty fields from the process environment:** `TS_CONTROL_URL`,
  `TS_AUTHKEY`/`TS_AUTH_KEY`, and OAuth or ID-token variables when `oauthkey` or `identityfederation`
  is linked. `node.start` sets `Dir`, `Hostname` and `ControlURL` and refuses to start when an auth-key
  variable is set; `oauthkey` and `useproxy` are omitted, and nothing imports `identityfederation`.
- **TLS trusts the wrong roots.** Go's `crypto/x509` reads only `/system/etc/security/cacerts`, missing
  Android 14's updatable Conscrypt APEX store, and ignores roots the user disabled
  ([golang/go#71258](https://github.com/golang/go/issues/71258)). `tlsdial` also falls back to baked-in
  Let's Encrypt roots. `roots_android.go` installs the APEX (or system) store minus the user's
  `cacerts-removed` with `x509.SetFallbackRoots`; `bakedroots` is omitted.
- **`LoginFinished` comes before the new node key is saved**
  ([#19149](https://github.com/tailscale/tailscale/issues/19149); draft fix
  [PR #19150](https://github.com/tailscale/tailscale/pull/19150)). `controlclient.Auto` saves `Persist`
  only with a network map, so closing right after `LoginFinished` can lose the login. `node.publish`
  clears the login URL only once the self node's key changes.
- **The state directory is not locked.** Two servers on one directory save their state over each
  other's. `TailnetNode.start` refuses a directory that an open node in the process uses; separate
  processes are not guarded.
- **`tsnet.Server.Dial` can leave the tailnet.** For addresses not in the network map, `tsdial.UserDial`
  falls back to the routing table, then a plain `net.Dialer`. `node.dial` resolves with `UserDialPlan`,
  requires `UseNetstackForIP` and connects with `NetstackDialTCP`.
- **Netstack can run `/system/bin/ping`** to relay ICMP echo to subnet or 4via6 addresses
  (`ProcessSubnets`, which tsnet enables). `advertiseroutes` and `appconnectors` are omitted, so the
  packet filter accepts only the node's own and service addresses. Other `os/exec` call sites are
  unreachable here (build tags, GOOS, omitted features, unset prefs, tsnet's fake TUN); re-audit them
  with `go list -deps` on each update.
- **Interface enumeration fails for app UIDs** ([#17311](https://github.com/tailscale/tailscale/issues/17311);
  proposed hook [PR #19455](https://github.com/tailscale/tailscale/pull/19455)): SELinux denies Go's
  `RTM_GETLINK`. `interfaces_android.go` registers a bionic `getifaddrs` getter with
  `netmon.RegisterInterfaceGetter`. Upstream's `feature/androidbin` fallback excludes `android && cgo`.
- **netmon misses Android network changes.** It polls every ten minutes and cannot find the default
  route, so changes never trigger a rebind, re-STUN or DERP reconnect. `TailnetNode` follows the best
  `INTERNET`, `NOT_VPN` network, which `default_network.go` reports with `netmon.UpdateLastKnown*` and
  `InjectEvent` on each node's monitor (from the unstable `Server.Sys().NetMon`).
- **`Server.Close` must not run before or during `Start`**, and LocalAPI `Start` ignores its context and,
  after `Close`, starts a control client that retries forever. `TailnetNode.start` returns only started
  nodes, `node.close` cancels calls and waits for them (`lifecycle` lock), and `login` runs LocalAPI
  `Start` uncancelled.
- **No automatic re-login.** After key expiry a running node reports `NeedsLogin` but requests no login
  URL; only `Server.Start` does. After logout the new profile has default prefs (Tailscale's control
  URL, the device hostname). `login()` restores tsnet's prefs with LocalAPI `Start` when they differ or
  say logged out, then calls `StartLoginInteractive`. Upstream's private `beginLoginAs`
  ([PR #21857](https://github.com/tailscale/tailscale/pull/21857)) does the same but is not in LocalAPI.
- **The IPN bus reports logout, key expiry and device removal alike as `NeedsLogin`.** `node.publish`
  reports removal while the health tracker has the `node-removed` warning, otherwise expiry once the
  latest key expiry has passed.
- **A watch that falls behind ends with an `ErrMessage`.** `node.publish` ignores that text
  (`watchFellBehind`, a copy of the unexported `watchIPNBusFellBehindMessage`).

## Limits

- **Another app's VPN.** Apps cannot see a VPN's underlying networks, so nodes follow the best non-VPN
  network. Split tunnels, such as the Tailscale app's, leave node traffic on that network (tested
  live). With a full tunnel, such as an exit node, traffic uses the VPN while nodes follow the network
  below it; this is untested with live traffic.
- Live-tailnet coverage is still needed for network changes without another VPN, 16 KiB devices,
  restrictive NATs, subnet routes, split DNS, IPv6 peers, throughput, prolonged outages, scheduled key
  expiry, revocation while stopped and Play split delivery.
- Local tests use upstream's `testcontrol`, which ignores a logout's expiry (control-side
  deregistration is untested) and cannot remove a running node (removal is simulated with a 404 at the
  node's next start). It does not build with `ts_omit_tailnetlock`.

## Updating upstream

1. Pin a full `tailscale/tailscale` main SHA in `go.mod`/`go.sum`, `TailnetNodeTest`'s version
   assertion, this file and `TAILSCALE-MODIFICATIONS`; review the patches and update their hashes (never
   just accept new ones), and recheck every entry.
2. Re-audit environment reads, filesystem writes, `os/exec` reachability, state file permissions and
   `tailcfg.PeerChange` fields (`watchStatus` refreshes peers only for online changes), and check that
   existing state still loads.
3. Update the notice inventories (THIRD-PARTY-NOTICES.md), rebuild both ABIs and run the device tests.
4. When the omitted feature set changes, revalidate it on a real tailnet.
