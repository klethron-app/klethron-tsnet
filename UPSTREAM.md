# Upstream issues

Pinned: Tailscale [`e0d532cca9be`](https://github.com/tailscale/tailscale/commit/e0d532cca9beeb8ed235bf30b9198a7c0e0afa02)
with Go 1.27.4. Reviewed 2026-11-13. Remove patches when equivalent fixes reach the pin.

The update from `59064619a0f4` extracts the network-map cache's file store and gates home-DERP
cache writes on `CacheNetworkMaps`. `ts_omit_cachenetmap` still disables cache creation, loading,
writes and clearing; the new close calls return immediately because no cache exists. All patched
files, transitive dependency versions, storage formats and `tailcfg.PeerChange` fields are unchanged.

This pin already includes the [netstack shutdown fix](https://github.com/tailscale/tailscale/pull/21668)
needed by [Tailcat #151](https://github.com/tailscale/tailcat/issues/151); no backport is needed.
It also already checks UDP DNS reply sources and transaction IDs, covering
[PR #20654](https://github.com/tailscale/tailscale/pull/20654).

## Patched

- **Saved nodes wait for network probing before connecting to their home relay.** Seed the initial
  home DERP from the self node in a fresh control response, after applying `OnlyTCP443`. Accept only
  a relay-capable region in that response's DERP map which is not avoided or excluded from home
  selection, and only while no home is selected. Ordinary network probing continues and can replace
  an unreachable or suboptimal hint. Publish home changes under magicsock's selection lock so the
  backend observes ordered old/new regions even when probing races the hint. Cached maps retain their
  upstream behavior; this does not cache authorization or bypass `Running`. `StartupTest` covers
  validation, selection races, missing/unreachable hints and complete first-`Running` peer snapshots.
  The wrapper serializes metadata reads through publication and includes a fresh read with `Running`;
  a background read cannot later overwrite that snapshot with pre-startup peer data.
- **Test control keeps old keys after noninteractive rotation.** It deletes them only after a
  browser followup, leaving duplicate peers when `RequireAuth` is false. A debug-only patch also
  retires them when authorization is unnecessary; `SecurityTest` covers the rotation.
- **A failed logout cannot be retried** ([#22248](https://github.com/tailscale/tailscale/issues/22248);
  fix [PR #22249](https://github.com/tailscale/tailscale/pull/22249)). `TryLogout` forgets the node key
  even on failure. `BuildGoLibrary.kt` patches a hash-checked copy of `control/controlclient/direct.go`
  to forget it only on success. See `TAILSCALE-MODIFICATIONS` and `LoginTest.failedLogoutKeepsTheLoginForRetries`.
- **Names outside the tailnet's DNS go to the device's resolver.** When no tailnet DNS route covers a
  name and its tailnet queries fail, `tsnet` `dnsResolver.lookupCandidate` asks `net.DefaultResolver`,
  which sends the name to the device's network and lets its DNS answer with any address, including a
  reachable peer's. Upstream has no option to disable this. Patch: count that case as a failed lookup,
  in a hash-checked copy of `tsnet/dns_lookup.go`. `DialTest.reachesOnlyTheTailnet` fails without it.
- **DNS-over-TCP and HTTPS can bypass netstack on Android.** UDP already checks `UseNetstackForIP`,
  but TCP and DoH select `SystemDial` unless the `user-dial-routes` capability is enabled
  ([#14854](https://github.com/tailscale/tailscale/issues/14854), proposed
  [PR #15259](https://github.com/tailscale/tailscale/pull/15259)). Following the
  [TailSocks fix](https://github.com/bropines/tailsocks/blob/d7f1e574fe293adc2e81b8aa24085c53e9afa56c/appctr/patches/14-dns-forwarder-netstack.patch),
  patch `forwarder.getDialerType` to dial matching IP literals directly with `NetstackDialTCP`.
  Other destinations retain upstream's dialer selection. `DnsTest` exercises a tailnet resolver's
  UDP answers and TCP retries after truncation or silence; `TrustRootsTest` checks DoH dialer selection.
- **Mixed-case split-DNS routes do not match queries.** Route keys preserve case while queries are
  lowercased. Patch `dnsname.FQDN.Contains` to compare case-insensitively, retaining label boundaries
  ([PR #22380](https://github.com/tailscale/tailscale/pull/22380)). `DnsTest` covers an uppercase route,
  a mixed-case query and a nonmatching label suffix.
- **DNS name validation skips the final label.** `dnsname.ToFQDN` accepts an empty or overlong
  final label after removing the trailing dot. Include the final label in its validation loop
  ([PR #22107](https://github.com/tailscale/tailscale/pull/22107)); the debug parser probe covers
  malformed names and valid label length boundaries.
- **The dial planner exposes only the first DNS address.** Add `Server.LookupIPForDial` to
  retain all addresses from fast peer and Service name lookups, falling back to `Server.LookupIP`
  for other names. Following [Haven #691's fix](https://github.com/GlassHaven/Haven/commit/2bd2338c92a8a7207ba233ea8c3da2e699e514b6),
  `node.dial` checks every candidate with `UseNetstackForIP`, then uses upstream `netx.RaceDial`
  to stagger netstack attempts under the caller's deadline. Losing and late connections close;
  logout and node shutdown cancel the attempts. Peer names still work with MagicDNS disabled.
- **Service DNS records overwrite other addresses for the same name.** Preserve both IPv4 and IPv6
  Service VIPs in `nodeBackend.extraDNS` and select the requested family, following
  [PR #22292](https://github.com/tailscale/tailscale/pull/22292). This lets the multi-address dialer
  try either family instead of inheriting whichever record arrived last. The single-address path
  reuses upstream's family matcher without changing or allocating in ordinary peer lookups.
- **Status reads serialize the whole peer list through HTTP and JSON.** A `Server.Status` accessor
  reads the backend directly. It also refreshes the self user's profile from the live incremental
  cache: upstream status reads otherwise use the profile frozen at the last full map. Called only
  between startup and watcher shutdown.
- **The login logger polls even with logging discarded.** Remove the `printAuthURLLoop` launch;
  our IPN watcher already receives login URLs. This saves two goroutines and five-second status polls
  while awaiting initial login.
- **A custom store still requires a directory.** Patch `tsnet/tsnet.go` to require an injected
  `StateStore`, skip directory and executable discovery, and leave the backend var-root empty.
  Java callbacks own persistence; no directory lock or file-store fallback remains.
- **Tailnet Lock authority storage bypasses `StateStore`.** Patch authority loading and bootstrap
  to use `javaStore`'s callback-backed storage. Each profile has one atomic snapshot containing
  signed updates, their original commit times and the compaction ancestor. Upstream compaction
  removes old updates from the snapshot, retaining at least 24 updates, the last 14 days and
  required chain history; this is not a hard byte cap. Profile deletion and logout remove it entirely.
  Invalid saved history and callback errors stop the node. No authority directory is used.
- **TLS discovers roots through filesystem reads.** Android supplies enabled system certificates via
  `AndroidCAStore`; patch both `tlsdial` verification paths and the DNS-over-HTTPS transport to use
  that explicit pool. This includes DERP relays whose `CertName` differs from their TLS server name.
  `TrustRootsTest` checks acceptance, untrusted-root and wrong-name rejection, and DoH trust and routing.
  `bakedroots` is omitted.
  This also fixes Go missing Android 14's updatable Conscrypt store and user-disabled roots
  ([golang/go#71258](https://github.com/golang/go/issues/71258)).

## Worked around

- **Logs can contain login URLs.** `main.go` discards `tsnet.Server.UserLogf`, `Logf` and Go's standard
  log output, which netmon and others use directly.
- **Initial watch snapshots bypass the browser URL filter.** While Running, they carry pending
  renewals only in `InitialStatus.AuthURL`. `node.publish` restores nonempty URLs on reconnect; only a
  changed node key confirms persistence. Empty URLs can arrive earlier.
  `loginURL` enforces HTTPS and tailscale.com hosts, rejects userinfo, and permits HTTP only for
  an explicitly selected loopback test server at the same origin.
- **`tsnet.Server` fills empty fields from the environment.** `node.start` sets `Hostname` and
  `ControlURL`, overriding `TS_CONTROL_URL`, and rejects `TS_AUTHKEY`/`TS_AUTH_KEY`. `oauthkey` and
  `useproxy` are omitted; nothing imports `identityfederation`, which reads ID-token variables.
- **`LoginFinished` comes before the new node key is saved**
  ([#19149](https://github.com/tailscale/tailscale/issues/19149); draft fix
  [PR #19150](https://github.com/tailscale/tailscale/pull/19150)). `controlclient.Auto` saves `Persist`
  only with a network map, so closing right after `LoginFinished` can lose the login. `node.publish`
  clears old URLs when the node key changes, then applies any pending URL in that notification.
  Storage callback exceptions stop the node, including failures upstream only logs.
- **`tsnet.Server.Dial` can leave the tailnet.** For addresses not in the network map, `tsdial.UserDial`
  falls back to the routing table, then a plain `net.Dialer`. `node.dial` requires `UseNetstackForIP`
  for each resolved candidate, normalizing IPv4-mapped IPv6 addresses before checking the route.
  It connects with `NetstackDialTCP` and closes connections that arrive after timeout.
- **Android NIO cannot adopt a socket descriptor.** `TailnetNode.connect` creates a private loopback
  TCP pair to return a standard `SocketChannel`. Setup is non-blocking and verifies the accepted
  socket's remote address against the client's local address before passing a duplicate to Go.
  The listener closes before tailnet connection setup. Socket addresses and options apply to the
  local bridge, not the tailnet peer.
- **Android's channel-backed streams serialize reads and writes.** `SocketAdaptor`'s input stream
  and `Channels.newOutputStream` hold the channel's `blockingLock` while doing I/O. Use streams for
  sequential request/response exchanges; use `SocketChannel.read`/`write` directly or a selector for
  concurrent traffic.
- **Netstack can run `/system/bin/ping`** to relay ICMP echo to subnet or 4via6 addresses
  (`ProcessSubnets`, which tsnet enables). `advertiseroutes` and `appconnectors` are omitted, so the
  packet filter accepts only the node's own and service addresses. Other `os/exec` call sites are
  unreachable here (build tags, GOOS, omitted features, unset prefs, tsnet's fake TUN).
- **Interface enumeration fails for app UIDs** ([#17311](https://github.com/tailscale/tailscale/issues/17311);
  proposed hook [PR #19455](https://github.com/tailscale/tailscale/pull/19455)): SELinux denies Go's
  `RTM_GETLINK`. `interfaces_android.go` registers a bionic `getifaddrs` getter with
  `netmon.RegisterInterfaceGetter`. Upstream's `feature/androidbin` fallback excludes `android && cgo`.
- **netmon misses Android network changes.** It polls every ten minutes and cannot find the default
  route, so changes never trigger a rebind, re-STUN or DERP reconnect. `TailnetNode` follows the best
  `INTERNET`, `NOT_VPN` network, which `default_network.go` reports with `netmon.UpdateLastKnown*` and
  `InjectEvent` on each node's monitor (from the unstable `Server.Sys().NetMon`). The new
  `Server.SystemDial` hook covers host-network dials such as control, but excludes DERP and WireGuard;
  it does not replace these network-change notifications.
- **Android can replace a network without changing its interface or addresses.** Following
  [Android PR #912](https://github.com/tailscale/tailscale-android/pull/912), retain the network
  identity across temporary loss and rebind when a replacement reuses the interface. A plain
  `InjectEvent` only refreshes netmon's snapshot; unchanged interfaces do not trigger a rebind.
- **`Server.Close` must not run before or during `Start`**, and LocalAPI `Start` ignores its context and,
  after `Close`, starts a control client that retries forever. `TailnetNode.start` returns only started
  nodes, `node.close` cancels calls and waits for them (`lifecycle` lock), and `login` runs LocalAPI
  `Start` uncancelled.
- **No automatic re-login.** Key expiry reports `NeedsLogin` without requesting a URL; logout resets
  the control URL and hostname. `login()` restores tsnet's prefs with LocalAPI `Start` if needed,
  then calls `StartLoginInteractive`. Upstream's private `beginLoginAs`
  ([PR #21857](https://github.com/tailscale/tailscale/pull/21857)) does the same but is not in LocalAPI.
- **Authentication operations are not atomic across LocalAPI calls.** Serialize login and logout;
  check cancellation while queued and between login steps. HTTP handlers can outlive cancellation,
  so logout calls the backend directly and each login mutation waits for completion. A changed node
  key ends access granted to the previous key, even while Running.
- **The IPN bus reports logout, key expiry and removal as `NeedsLogin`.** `node.publish` distinguishes
  removal by the `node-removed` health warning, otherwise expiry by the latest key expiry time.
- **User profiles can change without a self or peer update.** Following
  [Android PR #897](https://github.com/tailscale/tailscale-android/pull/897), `watchStatus` refreshes
  status for `UserProfiles` notifications too. Otherwise `loginName` can remain stale until another
  event arrives. `DialTest` sends a profile-only control update and checks the new name while the
  node, addresses and peer list remain unchanged.
- **A watch that falls behind ends with an `ErrMessage`.** `watchStatus` treats `watchFellBehind`
  (upstream's unexported `watchIPNBusFellBehindMessage`) as terminal without waiting for EOF.
  Any watch interruption ends previously granted access for dials and relays: missed
  events can include logout, expiry or a different login. A new Running snapshot cannot revive that access.
  Android's watcher fixes ([#941](https://github.com/tailscale/tailscale-android/pull/941),
  [#945](https://github.com/tailscale/tailscale-android/pull/945)) reinforce restarting interrupted watches;
  this library also revokes access across the gap.

## Limits

- **Roaming can pause traffic while a peer trusts an old endpoint**
  ([#22337](https://github.com/tailscale/tailscale/issues/22337), proposed
  [PR #22359](https://github.com/tailscale/tailscale/pull/22359)). A local rebind does not eliminate
  the remote peer's stale-path delay; the proposed transport change is not backported.
- **Local IPv6 endpoints can advertise the IPv4 socket's port.** When the independently bound
  sockets have different ports, this can prevent a direct IPv6 path
  ([PR #21755](https://github.com/tailscale/tailscale/pull/21755)). The proposed change remains
  unpatched pending targeted runtime coverage; the local tailnet tests do not cover this case.
- **Tailnet Lock has no public status or signing API.** Authority persistence, restart, unsigned-peer
  filtering and compaction have local tests; live locked-tailnet operation remains unverified.
  See [TODO.md](TODO.md) for remaining coverage.
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
2. Re-audit environment reads, filesystem access, `os/exec` reachability, storage callbacks and
   `tailcfg.PeerChange` fields (`watchStatus` refreshes peers only for online changes), and check that
   existing state still loads.
3. Update the notice inventories (THIRD-PARTY-NOTICES.md), rebuild both ABIs and run the device tests.
4. When the omitted feature set changes, revalidate it on a real tailnet.
