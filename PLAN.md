# Klethron tsnet plan

## Vision

A small, precompiled Android library that lets an app join a Tailscale tailnet from inside its own
process, with no VPN, service or subprocess, and open TCP connections to tailnet peers. Klethron uses it
for SSH over Tailscale from an on-demand feature module, consuming a pinned Maven Central release
without building Go itself. The library does the Tailscale mechanics; the app keeps UI, login flow,
node lifetime and storage.

## Boundaries

- Coordinates `com.klethron:tsnet`, package `com.klethron.tsnet`. API 34 minimum;
  arm64-v8a and x86_64 only, 16 KiB aligned.
- Library owns tsnet and JNI. Consumers own UI, authorization flow, node lifetime, no-backup state
  directories and transport adapters. Klethron's base app must not load native code or start a node.
- No SSH, SOCKS proxy, Android service, subprocess, downloaded executable or device-wide VPN.

Upstream bugs and workarounds that shape this plan are in [UPSTREAM.md](UPSTREAM.md).

## Done

### Build

- Pinned: Tailscale main `62d055f4c38b610d63a8cee7fa4a7e65b01564eb` (Go module
  `v1.105.0-pre.0.20261108232751-62d055f4c38b`), Go 1.27.3, NDK 30.0.16248370, AGP 9.5.1, Gradle 9.9.0
  (wrapper checksum pinned), JDK 25.0.5 in CI. CI validates the wrapper and runs actionlint before the build.
- `:tsnet` packages Java, C JNI and a Go `c-shared` library into an AAR. Native tasks are incremental and
  configuration-cache compatible; shared build caching stays disabled until toolchains are hermetic.
- Release libraries: `-trimpath`, stripped, 16 KiB aligned. `verifyNativeAar` checks architecture,
  alignment and that no debug probe exports or JNI bindings ship in release.
- Two upstream patches are applied to an isolated, hash-checked build copy (UPSTREAM.md): a failed
  logout keeps the node key, and a netmap still being handled cannot undo a logout. Two earlier
  logtail patches and the `ts_omit_acme` build fix were merged upstream (2026-11-08).
- Release publication, verified 2026-11-09: the POM carries Central's licence, developer and SCM
  metadata, and `<scm><tag>` names the release tag, whose commit pins Tailscale, Go and the NDK. Sources
  and a JDK Javadoc jar (119 KB) are attached; AGP's Dokka Javadoc dropped Markdown code spans and links.
  `check` runs doclint. Two `-Pversion=0.1.0` builds from different checkout paths with separate Go build
  caches gave byte-identical AAR, POM, module and jars (same host and toolchains).
- Tag builds hand the publication to a `release` job in the protected `maven-central` environment that
  runs no Gradle. `.github/central-bundle.sh` checks the artifact set and checksums, signs in a temporary
  keyring and zips a Portal bundle; with a throwaway key, all signatures verified against only the public
  key, and SNAPSHOT or mismatched versions, extra files, bad checksums and a wrong passphrase failed.
  Approving the job is the one human gate: from 0.1.1 it publishes automatically once Central validates
  the upload (0.1.0 was uploaded for manual publication and validated on 2026-11-09). Otherwise
  publishing goes only to `build/maven/`.

### Public API (`TsnetNode`, `TsnetStatus`)

- `start`, `awaitStatus`, `dial`, `logout`, `login`, `close` and static `setDefaultNetwork`; contracts
  are in the Javadoc. Go maps never-reused node IDs to servers; raw pointers never cross JNI. No
  finalizer: callers close.
- `TsnetStatus.revocation()` tells key expiry and device removal from logout; `error()` keeps the latest
  backend error (≤ 256 printable ASCII) until the next `RUNNING`. `toString()` omits URL and error text.
- Annotations: JSpecify `@NullMarked` (exported `api` dependency), Error Prone `@CheckReturnValue`,
  AndroidX thread and `@RequiresPermission(INTERNET)` (`compileOnly`; tools match by name). Verified
  2026-11-09 with AGP 9.5.1 lint in a scratch app using the published AAR: missing INTERNET is
  `MissingPermission`, calls from `@MainThread` are `ThreadConstraint` errors, an ignored `awaitStatus`
  is a `CheckResult` warning. Lint does not check the constructor or try-with-resources' `close()`.
- Decided 2026-11-09: no ephemeral mode and no caller-supplied hostinfo until a caller needs them.
  Control removes an offline ephemeral node, forcing a new browser login; forgetting is logout plus
  deleting the directory. Android hostinfo reports OS `android` only (no version, model or uname).

### Platform integration and privacy

- Interface discovery via bionic `getifaddrs`; network changes via `setDefaultNetwork`.
- TLS trusts only Android's system CA store (Conscrypt APEX, minus user-disabled roots).
- All upstream loggers discard output; only `tailscaled.state` is persisted.
- No subprocesses: the 21 upstream `os/exec` call sites in the release graph are unreachable here
  (UPSTREAM.md), audited 2026-11-09.
- 19 upstream features are omitted in both variants (`tsnet/build.gradle.kts`). x86_64 ZIP savings,
  measured individually (non-additive) on debug, † on the earlier release scaffold, ‡ on release:

  | Omitted | ZIP saved | Reason |
  |---|---:|---|
  | `acme` | 371,786 ‡ | No certificates: the node has no TLS listeners |
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
  `c2n`, `netstack`. `serve` cannot be omitted yet (UPSTREAM.md). Remeasure with:

  ```sh
  ./gradlew --no-configuration-cache -I gradle/native-feature-audit.init.gradle \
    -PauditTags=ts_omit_logtail :tsnet:verifyNativeAar   # then ./gradlew build to restore
  ```

### Crash diagnostics

Measured 2026-11-09 on the three devices of the `crash-*` row below, and checked by `CrashTest`:

- Android starts app processes with `AT_SECURE=1` (read on the Pixel), so Go runs in secure mode with
  `GOTRACEBACK=none` and writes no goroutine traceback, not even to `debug.SetCrashOutput`.
- An unrecovered panic, including a nil dereference, writes only `panic: <value>` (and a
  `[signal SIGSEGV …]` line) to logcat tag `Go`, then aborts: tombstone and `REASON_CRASH_NATIVE`. A
  runtime fatal error writes `fatal error: <message>` and exits with status 2: `REASON_EXIT_SELF`, no
  tombstone, indistinguishable from `System.exit(2)` except for that line.
- On the Pixel, the tombstone backtrace has one frame, the abort inside Go's runtime: the libraries'
  unwind tables cover only their C code. Symbol tables or a build ID would name only that runtime
  frame, never the panic site, so the libraries stay stripped.
- Decided 2026-11-09: keep Go's secure-mode default. `debug.SetTraceback("single")` restores panic
  tracebacks (function, module path and line; the negative control), but each frame prints raw
  argument words, which can hold key material passed by value. Fatal errors exit before any traceback
  regardless. The panic value is upstream text that this library cannot filter.

### Licences

- `GO-MODULES.tsv` lists the 26 Go modules in release build metadata (31 in debug), with verbatim
  notices; `build` checks both ABIs against it byte-for-byte.
- Per-file review, 2026-11-09: all 2,756 files compiled into the release libraries, generated data,
  embedded resources and the linked C runtime. `FILE-NOTICES` adds what module licences miss: Sun,
  Cephes and Inferno code in Go, gVisor's BSD `pkg/sync`, half-rs, FiniteStateEntropy and the NDK's
  bionic `crtbegin_so.o`/`crtend_so.o` (the only statically linked NDK code); `UNICODE-LICENSE` covers
  generated Unicode tables. `build` fails until the review is repeated for a new Go, NDK or module set.
  Details in `THIRD-PARTY-NOTICES.md`.

### Verification

Use an SELinux Enforcing device (`adb shell getenforce`): a permissive one hides the interface-discovery
failure. Count tests from JUnit XML under `build/outputs/androidTest-results/connected/`. Reports are
under each module's `build/reports/device-verification/<name>/`.

Instrumentation runs, all SELinux Enforcing, zero failures/errors/skips:

| Date | Device / API / ABI / page size | Tests | Report |
|---|---|---|---|
| 2026-11-09 | Emulators API 34 / x86_64 / 4 KiB and API 37.2 / x86_64 / 16 KiB; Pixel 11 Pro XL API 37 / arm64-v8a / 4 KiB (USB) | 27 library | `revocation-api34-4k`, `revocation-api37.2-16k`, `hardware-pixel11-api37-4k-usb-revocation` |
| 2026-11-09 | Emulator API 37.2 / x86_64 / 16 KiB | 2 R8-minified demo | `demo/…/thread-annotations-api37.2-16k` |
| 2026-11-09, pin `62d055f4c38b` | Emulator API 37.2 / x86_64 / 16 KiB; Pixel 11 Pro XL API 37 / arm64-v8a / 4 KiB (USB) | 23 library each, 2 demo on the emulator | `upstream-62d055-api37.2-16k`, `upstream-62d055-pixel11-api37-4k-usb`, `demo/…/upstream-62d055-api37.2-16k` |
| 2026-11-09 | Emulators API 34 / x86_64 / 4 KiB and API 37.2 / x86_64 / 16 KiB | 2 demo each, including rotation with a pending login; it fails without `configChanges` | `demo/…/recreation-emulator-5556`, `recreation-emulator-5558`, `recreation-negative-noconfigchanges-emulator-5558` |
| 2026-11-09 | Emulators API 34 / x86_64 / 4 KiB and API 37.2 / x86_64 / 16 KiB; Pixel 11 Pro XL API 37 / arm64-v8a / 4 KiB (USB) | 26 library each; both backpressure tests fail with an unbounded relay | `nonblocking-emulator-5556`, `nonblocking-emulator-5558`, `nonblocking-pixel11-api37-4k-usb`, `nonblocking-negative-unbounded-emulator-5558` |
| 2026-11-09 | Emulators API 34 / x86_64 / 4 KiB and API 37.2 / x86_64 / 16 KiB; Pixel 11 Pro XL API 37 / arm64-v8a / 4 KiB (USB) | 29 library each, including 3 crash tests; both panic tests fail with `SetTraceback("single")` | `crash-api34-4k`, `crash-api37.2-16k`, `crash-pixel11-api37-4k-usb`, `crash-negative-traceback-pixel11-api37-4k-usb` |
| 2026-11-09, logout patch | Emulator API 37.2 / x86_64 / 16 KiB; Pixel 11 Pro XL API 37 / arm64-v8a / 4 KiB (USB) | 29 library each; on the Pixel, 32 more runs of `logoutRequiresANewLogin` (`am instrument`), all OK, against 4 failures in 16 before | `logout-race-fixed-api37.2-16k`, `logout-race-fixed-pixel11-api37-4k-usb` (with `repeat/`) |

Non-blocking use of a dialed descriptor, against the loopback peer: an empty read fails with `EAGAIN`,
and `Os.poll` and `MessageQueue` descriptor events report readiness. Unread data stalled the writer
after 1.4–1.8 MiB in either direction (netstack grows its windows only as data is read; its 8 MiB
receive and 6 MiB send buffers are the ceiling), and the stream then arrived intact. One-byte loopback
round trips took a median of 64–125 µs on the emulators and 639–826 µs on the Pixel: relay overhead,
not network latency. A write after the node closed threw `EPIPE`; no `SIGPIPE` ended the process.

`NetworkChangeTest` switches the physical default network, so on hardware run it over USB, not
wireless ADB. `ControlPlaneTest` and the demo's live-start test need internet.

Real tailnet, on the Pixel above with the R8-minified release demo, a separate computer as the echo
peer, and path evidence from the peer's own status (`demo/build/reports/device-verification/hardware-pixel11-api37-4k-usb-<name>/results.json`):

- 2026-11-08, user-reported manual run: browser login, echo by IPv4 address and MagicDNS name,
  saved-login restart without the browser, logout and re-login. Path not observed.
- `live`: fresh echoes on Wi-Fi (50 ms), after switching to cellular (1,126 ms) and back (37 ms), with
  the same node. Dial-plus-exchange timings, not handoff durations.
- `open-stream`: one kept-open TCP stream carried six echoes across Wi-Fi → cellular → Wi-Fi; the peer
  saw one unchanged connection. First echoes after the switches took 3,418 and 102 ms.
- `paths-logout`: direct paths on Wi-Fi (LAN) and on LTE through the carrier's NAT. Logout ended the
  open stream (`ErrnoException`, peer stuck in FIN-WAIT-1) and fresh dials (`SocketTimeoutException`);
  browser re-login restored direct echoes.
- `fallback-dns`: with system DNS broken by an unresolvable strict Private DNS host, a fresh process
  reached RUNNING without the browser in 9.6 s and echoed by address and MagicDNS name. Which internal
  fallback resolved control is unobserved; cellular was not tested.
- `forced-derp`: an ephemeral tsnet peer with `TS_DEBUG_ALWAYS_USE_DERP=true` (no UDP sockets) relayed
  everything through DERP `hel`: fresh echoes on Wi-Fi and cellular, and one kept-open stream across
  Wi-Fi → cellular → Wi-Fi.
- `admin-expiry-removal` and `revocation-reasons`: API key expiry and admin-console removal showed
  `NEEDS_LOGIN (KEY_EXPIRED)` within 5 s and `NEEDS_LOGIN (NODE_REMOVED)` at the next sample, without a
  login URL or error. Access ended as for logout; re-login kept the node ID after expiry and gave a new
  ID and IPv4 after removal. Streams stayed open 15 min and 1 h 50 min on steady Wi-Fi.
- `live-recreation-api34-4k` (API 34 x86_64 emulator, 4 KiB, Chrome with Google sign-in): Android's
  cached-app freezer froze the demo 70–170 s after it left the foreground. A login approved meanwhile
  completed within 2 s of returning. Before `configChanges`, the deferred rotation relaunched the
  screen at that moment and the approved login was lost. With it, a kept-open stream survived five
  rotations, dark mode and font scale on one peer connection. After process death, Recents restored
  the screen and Start resumed the login without the browser.

**Not verified:** the live demo on 16 KiB (emulator Chrome dropped typed sign-in characters), a
DERP-to-direct upgrade, NATs that prevent direct paths, subnet routes, IPv6 peers, throughput, outages
longer than seconds, tailnet-period key expiry, revocation while not running, arm64 with 16 KiB pages,
backpressure or interactive latency on a real tailnet, or Play split delivery.

**Logout race, fixed 2026-11-09:** 4 of 16 Pixel runs of `logoutRequiresANewLogin` saw `STOPPED` with
the old addresses after `logout` returned (`crash-pixel11-api37-4k-usb-logout-flake`,
`logout-race-pixel11-api37-4k-usb`). The cause was upstream: a netmap being handled while the backend was
unlocked for tailnet lock was installed on the logged-out profile (UPSTREAM.md). On a host, with that
window widened by 300 ms, 5 of 10 runs failed the same way without the patch and none of 20 with it. In
that state `dial` waits for `RUNNING`, and a re-login reported `STARTING` with the old addresses. The
test now asserts that `NEEDS_LOGIN` after a logout has no addresses.

### Live-test harness

- The demo's opt-in live screen starts a node under `getNoBackupFilesDir()`, opens the login URL only on a
  browser-button tap, shows state, revocation, error and login persistence, logs out and in again, and
  forwards default-network changes. It echoes a fresh 52-byte challenge from one peer, optionally on a
  kept-open stream; dial and exchange are each bounded at 10 s. Nothing it handles is logged.
- Work runs on one serial worker, checked by lint's `ThreadConstraint` (verified 2026-11-09 by
  injecting wrong-thread calls; lint infers no thread for listener or `Handler.post` lambdas).
  Configuration changes are handled in place; destroying the screen queues close, and Start resumes
  the saved identity. No service, so the node is frozen with the app in the background.
- The procedure and evidence rules are in [README.md](README.md#live-tailnet-test).

### Current sizes (release, `tsnet/build/reports/native/sizes.tsv`)

| Entry | Raw bytes | ZIP bytes |
|---|---:|---:|
| arm64-v8a library | 16,729,744 | 6,519,181 |
| x86_64 library | 18,151,376 | 7,165,557 |

Complete release AAR: 13,771,517 bytes. Debug AAR: 14,878,667 bytes. Notices add about 67 KiB to the
release demo APK (59,526 compressed bytes plus ZIP headers).

## Future

In order: Klethron consumes a pinned Maven Central release, so release readiness comes first.

1. **Next: first Maven Central release, with the user.** Done 2026-11-09: the `com.klethron` namespace
   is verified; the signing key (v4 ed25519, certify-only primary offline, signing subkey until
   2028-11-08) is in the repository, keys.openpgp.org and keyserver.ubuntu.com; the `maven-central`
   environment allows only `v*` tags, requires the user's approval and holds the signing and Portal
   token secrets ([README.md](README.md#distribution)); the "Release tags" ruleset blocks moving or
   deleting `v*` tags, with no bypass. Version `0.1.0` is committed. Remaining: push `main`, tag `v0.1.0`,
   check that the CI-built publication matches a local build of the tag (cross-host reproducibility is
   unverified: CI uses Temurin, the dev host Homebrew OpenJDK, both 25.0.5), that the Portal validates
   the upload, and that a scratch app resolves the published artifact before Klethron depends on it.
2. **Integrate into Klethron** separately: update its TAILSCALE-PLAN.md, add the artifact only to
   `:klethron_tailscale`, test Play split loading and the SSH transport seam. Its `SessionLoop` runs a
   `java.nio` `Selector`, which has no public API to register a `ParcelFileDescriptor`: the adapter needs
   an `Os.poll` helper thread or `MessageQueue` descriptor events (both verified here), waking the
   selector. Measure SSH backpressure and interactive latency there on a real tailnet. Add peer listing
   with stable node IDs only with that caller. Without a foreground service, Android freezes a
   backgrounded process and its node; decide that lifetime there. Report library crashes there from
   `ApplicationExitInfo` on the next start, and add a way to keep the panic message (such as
   `debug.SetCrashOutput` to a caller-owned no-backup file) only if it needs one.

Blocked:

- **Verify the live demo on a 16 KiB device**, including browser return and activity recreation. The
  16 KiB emulator's Chrome drops typed sign-in characters; needs another browser path or hardware.
- **Revalidate feature exclusions** on a real tailnet, with the user. Reconsider `useexitnode`/`useproxy`
  only for a caller; omit `serve` once upstream fixes the build. Update the licence inventory for the
  final set.

Ongoing:

- **Extend real-tailnet coverage** for the cases under Not verified, only as a caller needs them. Use
  browser authorization, never a checked-in or CI auth key.
- **Keep the lifecycle audited.** On every upstream update, re-audit initialization, filesystem and
  subprocess reachability and state permissions (UPSTREAM.md), and retest the state format. Keep Go
  panic and allocation failure handling explicit.

## Updating upstream

Resolve `tailscale/tailscale` main to a full SHA; update the Go requirement and sums, the version
assertions (`TsnetNodeTest`, `MinifiedDemoTest`) and the documented SHA; re-check patch hashes and every
UPSTREAM.md entry; update both notice inventories (THIRD-PARTY-NOTICES.md); then rebuild both ABIs and
repeat the device tests.
