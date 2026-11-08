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
  Enforcing. Reports under each module's `build/reports/device-verification/`.
- Tests cover: native load and create/close cycles; interface parity with Java and a negative control
  showing stock Go discovery fails; file-free startup; a loopback two-node tailnet (testcontrol,
  DERP/STUN) with direct and DERP-only exchange; network-change rebinding on a real Wi-Fi/cellular
  switch; CA store parity with `AndroidCAStore`; real control-plane TLS and registration up to an
  unauthorized login URL (`ControlPlaneTest`, needs internet); public login, restart from state,
  logout, failed logout, key expiry, re-login, device approval; public dial by address and MagicDNS,
  end of stream, close, refused/unroutable/timed-out dials, logout ending access.
- The release demo uses R8/resource shrinking and exercises native lifecycle off the UI thread.
- **Not verified:** a real tailnet login, control-side logout/expiry, real DERP, fallback DNS,
  cross-device or NAT traversal, subnet routes, IPv6 peers, throughput, arm64 runtime, hardware,
  a non-blocking consumer, or Play split delivery.
- Seen once, unexplained: an API 37.2 logout test saw no status change after `STOPPED` for 30 s and the
  next close took 10 s. Watch for recurrence.

### Current sizes (release, `tsnet/build/reports/native/sizes.tsv`)

| Entry | Raw bytes | ZIP bytes |
|---|---:|---:|
| arm64-v8a library | 17,565,008 | 6,854,761 |
| x86_64 library | 19,071,024 | 7,536,474 |

Complete release AAR: 14,472,683 bytes. Debug AAR: 15,442,185 bytes. Notices add about 55 KiB to an APK.

## Future

1. **Prove a real tailnet.** Design an explicit live test: browser login on a test device through the
   public API, never a checked-in or CI auth key. Cover login, control-side logout and key expiry,
   re-authentication, real DERP, fallback DNS when system DNS fails, and cross-device/NAT paths including
   recovery after a network change.
2. **Finish the lifecycle.** Surface backend errors (`Notify.ErrMessage`) and control's node-removed state
   as typed status. Decide whether an ephemeral mode is
   needed before adding one. Keep auditing upstream initialization, filesystem/process behaviour and
   state permissions; retest the state format on every upstream update. Verify `@RequiresPermission` in
   a scratch consumer. Possibly let callers supply `hostinfo` OS version/device model.
3. **Validate `dial` with Klethron.** Use it from Klethron's non-blocking session loop (`Os.poll`/
   `MessageQueue`, not `SocketChannel`), including backpressure with a slow reader and interactive
   latency. Add peer listing with stable node IDs only with that caller. Keep Go panic and allocation
   failure handling explicit.
4. **Turn the demo into a network proof.** INTERNET permission, explicit setup, browser authorization,
   one destination and a simple exchange, all off the UI thread. No auth keys in tests or fork PRs.
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
