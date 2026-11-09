# Klethron tsnet plan

## Vision

A small, precompiled Android library that lets an app join a Tailscale tailnet from inside its own
process, with no VPN, service or subprocess, and open TCP connections to tailnet peers. Klethron uses it
for SSH over Tailscale from an on-demand feature module, consuming a pinned Maven Central release
without building Go itself. The library does the Tailscale mechanics; the app keeps UI, login flow,
node lifetime and storage.

## Boundaries

- Coordinates `com.klethron:tsnet:0.1.0-SNAPSHOT`, package `com.klethron.tsnet`. API 34 minimum;
  arm64-v8a and x86_64 only, 16 KiB aligned.
- Library owns tsnet and JNI. Consumers own UI, authorization flow, node lifetime, no-backup state
  directories and transport adapters. Klethron's base app must not load native code or start a node.
- No SSH, SOCKS proxy, Android service, subprocess, downloaded executable or device-wide VPN. Native work
  runs in the app process; blocking calls run off the UI thread.

Upstream bugs and workarounds that shape this plan are in [UPSTREAM.md](UPSTREAM.md).

## Done

### Build

- Pinned: Tailscale main `503010831ecd44ab3d86864e5e65ff9f8b969d88` (Go module
  `v1.105.0-pre.0.20261107202901-503010831ecd`), Go 1.27.3, NDK 30.0.16248370, AGP 9.5.1, Gradle 9.9.0
  (wrapper checksum pinned). CI validates the wrapper and runs actionlint before the build.
- `:tsnet` packages Java, C JNI and a Go `c-shared` library into an AAR. Native tasks are incremental and
  configuration-cache compatible; shared build caching stays disabled until toolchains are hermetic.
- Release libraries: `-trimpath`, stripped, 16 KiB aligned. `verifyNativeAar` checks architecture,
  alignment and that no debug probe exports or JNI bindings ship in release.
- Three upstream patches are applied to an isolated, hash-checked build copy (see UPSTREAM.md).
- Maven publishing goes only to `build/maven/`; no remote publication.

### Public API (`TsnetNode`, `TsnetStatus`)

- `start(stateDirectory, hostname)`: explicit control URL and hostname; identity persists in
  `tailscaled.state` (0600, directory 0700). Refuses `TS_AUTHKEY`/`TS_AUTH_KEY` in the environment.
- `awaitStatus(previous, timeout)`: latest state, login URL and addresses, bounded memory, no callbacks.
  The login URL is cleared only once the new node key is saved; callers close only after that.
- `dial(host, port, timeout)`: tailnet address or MagicDNS name; returns a socket-pair
  `ParcelFileDescriptor` relayed to a netstack TCP connection. Never uses the device's network.
- `logout(timeout)`, `login()` (after logout, key expiry, or to re-authenticate), `close()`.
  A failed logout keeps the login for retries. Device approval (`NEEDS_MACHINE_AUTH`) needs no extra API.
- Static `setDefaultNetwork(interfaceName, ipv4Gateway)` lets the app report default-network changes so
  started nodes rebind.
- Go maps never-reused node IDs to servers; raw pointers never cross JNI. No finalizer: callers close.
- Contracts: JSpecify `@NullMarked` (exported `api` dependency), Error Prone `@CheckReturnValue`,
  AndroidX threading and `@RequiresPermission(INTERNET)` annotations (`compileOnly`; tools match by name).
  Construction is `@WorkerThread`: the first node reads the CA store (10–20 ms cold).

### Platform integration and privacy

- Interface discovery via bionic `getifaddrs`; network changes via `setDefaultNetwork`.
- TLS trusts only Android's system CA store (Conscrypt APEX, minus user-disabled roots). User-added CAs
  and the app's network security configuration do not apply to native TLS.
- All upstream loggers discard output; logtail, socket logs, log configuration and netmap cache write
  no files. Only `tailscaled.state` is persisted.
- 18 upstream features are omitted in both variants (`tsnet/build.gradle.kts`). x86_64 ZIP savings,
  measured individually (non-additive) on debug, or † on the earlier release scaffold:

  | Omitted | ZIP saved | Reason |
  |---|---:|---|
  | `debug` | 109,325 | Removes `SSLKEYLOGFILE` key logging and debug endpoints |
  | `syspolicy` | 97,115 | No MDM policy store in an embedded library |
  | `peerapiserver` | 88,451 | Outbound only; no PeerAPI listener |
  | `useexitnode` | 65,076 | Dials tailnet and subnet hosts, not the internet |
  | `useproxy` | 55,863 | Host environment must not redirect control/DERP |
  | `appconnectors` | 34,026 | Never an app connector |
  | `usermetrics` | 31,167 | No metrics served |
  | `logtail` | 28,644 † | No log upload or disk buffering |
  | `cachenetmap` | 21,679 † | Would persist peer names, addresses and keys |
  | `oauthkey` | 20,253 | Removes `TS_CLIENT_SECRET` path and `golang.org/x/oauth2` |
  | `drive` | 14,694 | No Taildrive |
  | `exitnodehealth` | 14,262 | Requires `useexitnode` |
  | `advertiseroutes` | 4,887 | Never advertises routes |
  | `clientupdate` | 2,644 | Nothing to self-update |
  | `ssh` | 2,907 † | Out of scope; not imported by tsnet, enforces scope |
  | `advertiseexitnode` | 1,243 | Requires `peerapiserver` and `advertiseroutes` |
  | `bakedroots` | 961 | Trust only the platform store |
  | `webclient` | −596 † | Out of scope; enforces scope despite the small increase |

  Kept as core to a client: `dns`, `portmapper`, `health`, `gro`, `useroutes`, `tailnetlock`, `ipnbus`,
  `c2n`, `netstack`. `acme`/`serve` cannot be omitted (UPSTREAM.md). Remeasure with:

  ```sh
  ./gradlew --no-configuration-cache -I gradle/native-feature-audit.init.gradle \
    -PauditTags=ts_omit_logtail :tsnet:verifyNativeAar   # then ./gradlew build to restore
  ```

### Licences

- `GO-MODULES.tsv` lists the 26 Go modules in release build metadata (31 in debug, from test servers)
  with versions, checksums and verbatim notices; `build` checks both ABIs against it byte-for-byte.
  `TAILSCALE-MODIFICATIONS` documents the patches. See `THIRD-PARTY-NOTICES.md` for maintenance.

### Verification

- Device coverage: 26 instrumentation tests (25 library, 1 R8-minified demo), zero failures/errors/skips
  per device, on API 34 / x86_64 / 4 KiB pages and API 37.2 / x86_64 / 16 KiB pages, both SELinux
  Enforcing (before the live demo was added). Reports under each module's `build/reports/device-verification/`.
- Hardware, 2026-11-08: Pixel 11 Pro XL, API 37 / arm64-v8a / 4 KiB pages, SELinux Enforcing:
  **24 library + 2 R8-minified demo tests**, zero failures/errors/skips, counted from JUnit XML.
  Reports: each module's `build/reports/device-verification/hardware-pixel11-api37-4k/`.
  `NetworkChangeTest` was explicitly excluded because ADB was over Wi-Fi; use USB for that test.
  This verifies arm64 runtime and real control-plane TLS/registration, not an authorized real tailnet.
- USB follow-up, 2026-11-09, same Pixel/API/ABI/page size/SELinux: all **25 library tests** passed,
  zero failures/errors/skips and no exclusions, counted from JUnit XML. Includes
  `NetworkChangeTest.rebindsWhenDefaultNetworkChanges` (5.133 s), which switches the physical default
  network while its loopback test tailnet exchanges data. Reports:
  `tsnet/build/reports/device-verification/hardware-pixel11-api37-4k-usb/`.
- Manual real-tailnet verification, 2026-11-08: the user reported all guided steps passing on the
  same Pixel with the installed R8-minified release demo and a separate computer running a TCP echo
  server. Browser login reached RUNNING / Login saved; 52-byte echo passed by tailnet IPv4 address
  and full MagicDNS name; close/reopen resumed the saved login without browser authorization and
  echoed again; logout confirmed, browser re-login succeeded, and echo passed again. This is
  user-reported manual evidence, separate from the JUnit counts. Direct versus DERP was not observed.
- Real-tailnet network recovery, 2026-11-09: ADB-driven UI test of the installed R8-minified demo on
  the same Pixel, using its saved login and the same running node. Verified fresh 52-byte TCP echoes
  to the computer on Wi-Fi (50 ms), after disabling Wi-Fi and confirming CELLULAR as the default
  network (1,126 ms), and after restoring Wi-Fi as the default (37 ms). The demo process did not
  restart, and Wi-Fi was restored. These are dial-plus-exchange timings, not handoff/outage durations.
  Direct versus DERP was not observed. Evidence:
  `demo/build/reports/device-verification/hardware-pixel11-api37-4k-usb-live/results.json`.
- Open stream across network changes, 2026-11-09, same Pixel/API/ABI/page size/SELinux, USB ADB:
  with **Keep connection open**, one TCP stream to the computer carried six exact 52-byte echoes:
  two on Wi-Fi, two after Wi-Fi was disabled and CELLULAR became default (0.72 s), two after Wi-Fi was
  re-enabled and became default (2.40 s). The first echo after each switch took 3,418 ms and 102 ms;
  others 8–42 ms. `ss` on the peer showed the same single connection (unchanged source port) at every
  stage, and its removal when the option was unticked. Switches were about 8 s apart, below the echo
  server's 15 s idle timeout; longer outages were not tested. Direct versus DERP was not observed.
  Evidence: `demo/build/reports/device-verification/hardware-pixel11-api37-4k-usb-open-stream/results.json`.
  After this demo change, the 2 R8-minified demo tests passed (zero failures/errors/skips, JUnit XML) on
  API 37.2 / x86_64 / 16 KiB pages, SELinux Enforcing. A first attempt failed the live-start test (no
  control-plane status within 45 s) while the host's internet was down; the rerun after it returned passed.
- Paths and logout revocation, 2026-11-09, same Pixel/API/ABI/page size/SELinux, USB ADB, saved login.
  Path evidence came from the peer's tailscaled status during each exchange. Fresh 52-byte echoes went
  **direct** on Wi-Fi (LAN endpoint) and also on LTE: a public endpoint whose reverse DNS is the
  carrier's, so this was direct traversal between a carrier NAT and the peer's home NAT. With
  **Keep connection open**, an open stream plus a demo **Log out of tailnet** (confirmed within 6 s,
  NEEDS_LOGIN) ended access. Echo on the open stream failed at the 10 s bound (`ErrnoException`), and
  the peer's FIN on that connection was never acknowledged (FIN-WAIT-1). A fresh dial timed out
  (`SocketTimeoutException`), and the peer saw the node offline. Browser re-login, driven over ADB
  with an existing SSO session, restored direct echoes by address and MagicDNS name. DERP never
  carried data.
  Evidence: `demo/build/reports/device-verification/hardware-pixel11-api37-4k-usb-paths-logout/results.json`.
  The demo now enables echo whenever a node is started, so a lost access shows as a failure. Its 2
  R8-minified tests passed afterwards (zero failures/errors/skips, JUnit XML) on API 37.2 / x86_64 /
  16 KiB pages, SELinux Enforcing.
- Fallback DNS, 2026-11-09, same Pixel/API/ABI/page size/SELinux, USB ADB, saved login, on Wi-Fi.
  Strict Private DNS set over ADB to an unresolvable hostname made system DNS fail (`unknown host` for
  the control host, other names and the peer's MagicDNS name), while IP connectivity remained. A fresh
  demo process reached RUNNING / Login saved without a browser within 9.6 s of Start. Fresh 52-byte
  echoes then passed by tailnet IPv4 address (12/18/11 ms) and full MagicDNS name (11/10 ms), direct on
  the LAN per the peer's status. Android recreated the unvalidated Wi-Fi network several times
  meanwhile. The original unset Private DNS settings were restored. Which internal fallback resolved
  control is unobserved because upstream logs are discarded; DNS failure on cellular was not tested.
  Evidence: `demo/build/reports/device-verification/hardware-pixel11-api37-4k-usb-fallback-dns/results.json`.
- Real DERP, 2026-11-09, same Pixel/API/ABI/page size/SELinux, USB ADB, saved login. The peer was a
  separate unprivileged, browser-authorized, ephemeral tsnet echo node on the computer (pinned module)
  run with `TS_DEBUG_ALWAYS_USE_DERP=true`, so it opened no UDP sockets and no direct path could form.
  Its own status for the demo node showed no direct address and relay `hel` throughout, with byte
  counters growing during each exchange. Fresh 52-byte echoes passed on Wi-Fi by IPv4 address
  (67/41/41 ms) and MagicDNS name (56/51 ms), and on cellular (107/96/93 ms). One kept-open stream
  carried six echoes across Wi-Fi → cellular → Wi-Fi (the peer accepted it once; reuses 20–60 ms).
  The test peer was then logged out and left the tailnet. UDP was refused by the peer, not blocked
  by a network or on the device, and a DERP-to-direct upgrade was not observed.
  Evidence: `demo/build/reports/device-verification/hardware-pixel11-api37-4k-usb-forced-derp/results.json`.
- Tests cover: native load and create/close cycles; interface parity with Java and a negative control
  showing stock Go discovery fails; file-free startup; a loopback two-node tailnet (testcontrol,
  DERP/STUN) with direct and DERP-only exchange; network-change rebinding on a real Wi-Fi/cellular
  switch; CA store parity with `AndroidCAStore`; real control-plane TLS and registration up to an
  unauthorized login URL (`ControlPlaneTest`, needs internet); public login, restart from state,
  logout, failed logout, key expiry, re-login, device approval; public dial by address and MagicDNS,
  end of stream, close, refused/unroutable/timed-out dials, logout ending access.
- The release demo uses R8/resource shrinking. Two tests exercise native lifecycle and public-API
  startup/restart through the live screen, reaching browser-authorization readiness without opening
  or capturing a login URL. The latter needs internet. Browser login and real-tailnet TCP echo are
  covered separately by the manual and ADB-driven checks above, not by these JUnit tests.
- **Not verified:** admin-initiated key expiry/removal, a DERP-to-direct upgrade, traversal across
  NATs that prevent direct paths, subnet routes, IPv6 peers, throughput, open-stream survival of
  outages longer than seconds, arm64 with 16 KiB pages, a non-blocking consumer, or Play split delivery.
- Seen once, unexplained: an API 37.2 logout test saw no status change after `STOPPED` for 30 s and the
  next close took 10 s. Watch for recurrence.

### Live-test harness

- Opt-in demo screen starts the public API with identity under `getNoBackupFilesDir()`, opens the login
  URL only on a browser-button tap, shows backend state and login persistence, and supports logout and
  re-authentication. It forwards default-network changes through `ConnectivityManager`.
- One destination (tailnet IP or MagicDNS name) and port; a fresh 52-byte challenge must echo exactly.
  Dial and exchange each have a 10-second bound. **Keep connection open** reuses one stream across
  echoes until unticked, a failed echo or close. No URL, credential or peer payload is logged.
- Node creation, operations and cleanup run off the UI thread. Browser navigation keeps the node alive;
  destroying the screen queues close. Reopening requires Start, which resumes saved identity. Serial
  cleanup prevents overlapping access to the state directory on activity recreation. No service.
- [README.md](README.md#live-tailnet-test) has the peer echo setup, manual sequence and evidence matrix.
  The basic authorized cross-device flow passed manually as recorded above; the remaining path and
  failure scenarios still need explicit verification.

### Current sizes (release, `tsnet/build/reports/native/sizes.tsv`)

| Entry | Raw bytes | ZIP bytes |
|---|---:|---:|
| arm64-v8a library | 17,565,008 | 6,854,761 |
| x86_64 library | 19,071,024 | 7,536,474 |

Complete release AAR: 14,472,683 bytes. Debug AAR: 15,442,185 bytes. Notices add about 55 KiB to an APK.

## Future

1. **Extend real-tailnet coverage.** Basic browser login, address/MagicDNS echo, saved-login restart,
   logout/re-login, fresh dials and an open stream across Wi-Fi/cellular switches, direct paths on LAN
   and carrier NAT, logout ending an open stream and fresh dials, startup plus echo with failing system
   DNS, and fresh and kept-open echoes relayed only through real DERP passed on hardware. Next verify
   admin-initiated expiry/removal (needs the admin console) with an open stream and a fresh dial.
   Use browser authorization, never a checked-in or CI auth key. Record untested cases explicitly.
2. **Finish the lifecycle.** Surface backend errors (`Notify.ErrMessage`) and control's node-removed state
   as typed status. Decide whether an ephemeral mode is
   needed before adding one. Keep auditing upstream initialization, filesystem/process behaviour and
   state permissions; retest the state format on every upstream update. Verify `@RequiresPermission` in
   a scratch consumer. Possibly let callers supply `hostinfo` OS version/device model.
3. **Validate `dial` with Klethron.** Use it from Klethron's non-blocking session loop (`Os.poll`/
   `MessageQueue`, not `SocketChannel`), including backpressure with a slow reader and interactive
   latency. Add peer listing with stable node IDs only with that caller. Keep Go panic and allocation
   failure handling explicit.
4. **Verify the live demo on the remaining devices.** Repeat minified-consumer startup on API 34 and
   16 KiB devices, then authorized networking. Verify browser return and activity recreation; a
   destroyed screen intentionally needs Start again. No auth keys in tests or fork PRs.
5. **Revalidate feature exclusions** against the release baseline now that Start and Dial are reachable,
   on a real tailnet on API 34 and 16 KiB devices. Reconsider `useexitnode`/`useproxy` only for a real
   caller; omit `acme` once upstream fixes the build. Update the licence inventory for the final set.
6. **Release readiness.** Complete the licence review (per-file attributions, generated data, embedded
   resources, C/NDK runtime). Test R8 consumers with networking, arm64 hardware, cold-start loading.
   Keep native symbols separately if needed for crash reports. Record per-ABI sizes on dependency changes.
7. **Publish immutable versions** to Maven Central: verify the `com.klethron` namespace, signing, protected
   credentials, POM/source/Javadoc metadata, publish from tags. Version independently of upstream; record
   the upstream commit per release. Never overwrite a release.
8. **Integrate into Klethron** separately: update its TAILSCALE-PLAN.md, add the artifact only to
   `:klethron_tailscale`, test Play split loading and the SSH transport seam.

## Commands and maintenance

```sh
./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository
ANDROID_SERIAL=emulator-5554 ./gradlew :tsnet:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :demo:connectedReleaseAndroidTest
```

Use an SELinux Enforcing device (`adb -s <serial> shell getenforce`); a permissive emulator hides the
interface-discovery failure and fails its negative control.

To update upstream: resolve `tailscale/tailscale` main to a full SHA, update the Go requirement and sums,
the version assertion and documented SHA, re-check patch hashes and every UPSTREAM.md entry, then rebuild
both ABIs and repeat the device tests.
