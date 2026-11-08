# Klethron tsnet implementation plan

## Purpose and boundaries

Build and release a precompiled Android AAR in this repository. Klethron consumes a pinned Maven
artifact without compiling Go locally or in its CI. Keep the engine inside Klethron's on-demand
Tailscale feature; the base app must not acquire native libraries or initialize a node.

- Coordinates: `com.klethron:tsnet:0.1.0-SNAPSHOT`; Java package: `com.klethron.tsnet`.
- Minimum Android 14/API 34. Initial ABIs: arm64-v8a and x86_64. Require 16 KiB compatibility.
- Library owns tsnet and JNI mechanics. Consumers own UI, authorization flow, node lifetime,
  app-private no-backup state directories, and their application-specific transport adapters.
- No SSH implementation, local SOCKS proxy, Android service, subprocess or device-wide VPN.
- Native work stays inside the app process, using threads and goroutines. Blocking networking must
  run off the UI thread; no fork/exec or downloaded executable is part of the Android runtime design.

## Current checkpoint

Tailscale main is pinned to `503010831ecd44ab3d86864e5e65ff9f8b969d88` (2026-11-07 UTC), resolved
2026-11-08. The Go module pin is `v1.105.0-pre.0.20261107202901-503010831ecd`.
Go 1.27.3, NDK 30.0.16248370, AGP 9.5.1 and Gradle 9.9.0 are pinned.

- `:tsnet` packages Java, C JNI, and a Go `c-shared` library into an AAR.
- `TsnetNode` creates an **unstarted** `tsnet.Server` behind a `runtime/cgo.Handle` and closes it once.
  Java synchronizes close; raw Go pointers never cross JNI. There is no finalizer or automatic cleanup:
  callers must close explicitly. There is no start, login, status, or dial API yet.
- Native builds are incremental and configuration-cache compatible. Shared build caching is deliberately
  disabled until toolchains are provisioned hermetically. Only one installed Go version is accepted.
- Release libraries use Go `-trimpath`, stripped symbols/DWARF and 16 KiB linker alignment.
- Both variants omit 18 upstream features with `ts_omit_*` tags (listed in `tsnet/build.gradle.kts`):
  out-of-scope servers/integrations, logtail, debug hooks, metrics, the on-disk netmap cache, PeerAPI
  serving, exit nodes, environment proxies, OAuth secrets, MDM policy and baked-in TLS roots.
  Two local patches skip log configuration and socket-log directory initialization; both user/backend
  loggers discard output.
- `check` checks Android lint, native AAR architecture/alignment and both variants' Go module notices,
  and compiles JNI instrumentation.
- `:demo` is a platform-only Java Activity with an explicit offline native-load/lifecycle button.
- Public Java types use JSpecify non-null defaults, exported with the Maven dependency metadata, plus
  Error Prone `@CheckReturnValue` and AndroidX threading annotations as compile-only, name-matched
  contracts. The release demo exercises the native lifecycle off the UI thread with R8/resource shrinking.
- Maven publishing is configured only to `build/maven/`; there is no remote artifact publication.
- Android interface discovery uses bionic `getifaddrs` through `netmon.RegisterInterfaceGetter`.
  The adapter copies IPv4/IPv6 addresses and masks, queries index/MTU/flags with public ioctls, and
  supplies `AltAddrs` so Tailscale never falls back to Go's restricted address enumeration. It reports
  interfaces with IP addresses; MAC addresses and addressless links are not needed or collected.
  No Go/Tailscale fork, dependency version update, Java callback or synthetic interface is required.
- Debug native libraries include an instrumentation-only probe; release libraries exclude it via a
  build tag, checked by `verifyNativeAar`. The probe compares interface metadata and address prefixes
  with Java `NetworkInterface`, and creates/starts/closes upstream network monitors. Only the test APK
  requests INTERNET permission. The public Java API and offline demo remain at the scaffold stage.
- A second debug-only probe now starts and closes a node with in-memory identity and a rejecting
  loopback control stub. It verifies `NeedsLogin`, no assigned IP, and no files in its caller-owned
  no-backup directory. This does not establish live-tailnet networking or a public startup API.
- A third debug-only probe joins two in-process nodes to upstream's test control server and a loopback
  DERP/STUN server, then exchanges TCP data over a verified direct UDP path and over DERP only. This is
  a hermetic two-node tailnet, not a real tailnet, DNS/TLS bootstrap or public dial API.
- `TsnetNode.setDefaultNetwork` lets the host app report its default interface and IPv4 gateway; every
  started node then re-examines its network and rebinds when it changed. Verified with a real Wi-Fi to
  cellular change on emulators against the loopback tailnet, not on hardware or a real tailnet.
- Go TLS verification trusts Android's current system CA store (Conscrypt APEX on Android 14+, minus
  user-disabled roots) instead of stock Go's system-image directory. User-added CAs are not trusted,
  and `ts_omit_bakedroots` removes Tailscale's Let's Encrypt fallback, so no other roots are trusted.
- A debug-only probe resolves and verifies Tailscale's real control plane through the platform, then
  starts an ephemeral node until control offers a login URL. No credentials; not a joined tailnet.

Initial scaffold verification on 2026-11-08:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passed. Repeating the command
  reused the configuration cache and left both Go compilation tasks up to date. The generated POM has
  the intended coordinates and no Java runtime dependencies. Built-in Kotlin compilation and its
  implicit stdlib dependency are disabled for the Java-only Android modules.
- Instrumentation JUnit XML records two passed tests, zero failures/errors/skips on each of API 34
  (x86_64, 4,096-byte pages) and API 37.2 (x86_64, 16,384-byte pages). These test linked-version reporting
  and 100 create/close cycles including double close. No host JVM tests exist yet.
- Installed and opened the demo on API 37.2; its button reported the pinned version and successful
  create/close. `zipalign -c -P 16 4` passed for the debug demo APK. Both AAR libraries passed ELF
  architecture and 16 KiB LOAD-segment alignment checks.
- arm64 is cross-compiled and structurally checked, but has not been loaded on a device. No networking,
  login, R8-minified consumer or Play split delivery is verified by these tests.

Initial release AAR measurements from `tsnet/build/reports/native/sizes.tsv`:

| Entry | Uncompressed bytes | ZIP-compressed bytes |
|---|---:|---:|
| arm64-v8a library | 17,162,640 | 6,670,532 |
| x86_64 library | 18,611,024 | 7,337,187 |

The complete AAR is 14,013,835 bytes. These are scaffold sizes, not installed/download sizes of a
production app; future networking entry points and complete licence notices can increase them.

## Android interface iteration (2026-11-08)

Upstream investigation against the unchanged pinned revision:

- [Issue #17311](https://github.com/tailscale/tailscale/issues/17311) reports the Android tsnet startup
  failure and a working libc getter in another embedded app. [Open PR #19455](https://github.com/tailscale/tailscale/pull/19455)
  proposes the same opt-in hook. Our small Android-only implementation uses the existing hook directly;
  neither that PR nor the suggested Go toolchain patch needs to be carried here.
- Bionic's [getifaddrs source](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/bionic/ifaddrs.cpp)
  clarifies a detail in the discussion: it still uses permitted `RTM_GETADDR` netlink requests, skips
  restricted `RTM_GETLINK` for app UIDs, and fills in metadata via ioctls. It is not an IPv4-only
  `SIOCGIFCONF` implementation. Device tests must run as an ordinary app with SELinux enforcing.
- [Issue #21129](https://github.com/tailscale/tailscale/issues/21129) led to the standalone-binary
  `feature/androidbin` fallback. In this pin its registration explicitly excludes `android && cgo`,
  and its synthetic interface cannot describe the full multi-interface network. Enabling it with a
  build-tag patch is unnecessary here. The official Android app also uses the registered-getter hook,
  populated from Java instead of libc.
- Discovery alone does not supply network-change events. Pinned `net/netmon/polling.go` polls Android
  only every ten minutes, expecting host notifications. `interfaces_android.go` expects default-route
  and gateway updates from Android. Add a narrow host notification seam when a started node needs it;
  leave callback registration and app lifetime with the caller.
- Before exposing Start, silence `UserLogf` (which defaults to `log.Printf` and can include login URLs)
  and backend logs, and disable/audit logtail. The pinned `startLogger` creates log configuration and
  a disk buffer even before node startup completes; an in-memory state store alone is insufficient.
  Inspect the existing `ts_omit_logtail` build tag before considering a minimal upstream patch.
  `Start` is not context-aware and upstream forbids concurrent Start/Close, so lifecycle serialization
  and cancellation need an explicit design rather than wrapping these methods independently.

Verification:

- `./gradlew build` passes on Gradle 9.9.0; repeating it reuses the configuration cache with all four
  Go builds up to date. Release AAR architecture, 16 KiB alignment and absence of the test bridge pass.
- Instrumentation JUnit XML counts **five passed tests, zero failures/errors/skips per device** on
  API 34 / x86_64 / 4,096-byte pages and API 37.2 / x86_64 / 16,384-byte pages, both SELinux Enforcing.
  These cover the original lifecycle/version checks, IPv4/IPv6 interface parity, 50 monitor cycles,
  and a negative control confirming standard Go discovery returns `permission denied` in the same app.
  Reports are retained locally under `tsnet/build/reports/device-verification/`.
- The already-running API 37.2 emulator was Permissive and hid Go's discovery failure. Dedicated
  enforcing-mode emulators replaced it for this verification; SELinux mode was not recorded in the
  original scaffold checkpoint. Neither run proves Start/login/dial, network handover, or arm64 runtime.

Updated release sizes (no native dependency version changes; `x/sys` is now a direct dependency):

| Entry | Uncompressed bytes | ZIP-compressed bytes |
|---|---:|---:|
| arm64-v8a library | 17,173,456 | 6,673,374 |
| x86_64 library | 18,623,280 | 7,341,106 |

Complete AAR: **14,020,596 bytes**, up 6,761 bytes from the scaffold. Native libraries grew by
10,816 bytes (arm64-v8a) and 12,256 bytes (x86_64) uncompressed.

## Build tooling and CI (2026-11-08)

- Upgraded Gradle and regenerated its wrapper from 9.8.2 to the latest stable
  [9.9.0](https://docs.gradle.org/9.9.0/release-notes.html). Distribution SHA-256 is pinned and the
  wrapper JAR matches Gradle's published checksum. Wrapper scripts are unchanged by regeneration.
  [AGP 9.5.1](https://developer.android.com/reference/tools/gradle-api) is already the latest stable;
  the available 9.6 release is an alpha and is not selected.
- CI validates the wrapper with `gradle/actions/wrapper-validation` 6.4.1 pinned to a commit, then
  checks workflows with the official actionlint 1.7.12 Docker action pinned to its image digest.
  Both run before the Android build, with read-only repository permissions. Local actionlint passes.

## Feature exclusion iteration (2026-11-08)

Both release and debug builds now set `ts_omit_ssh`, `ts_omit_webclient` and `ts_omit_logtail`.
Build tags are explicit Gradle task inputs, with `klethron_test` added only for debug. No dependency
or toolchain versions changed, and no upstream patch is carried.

Audit of the pinned source:

- `tsnet` does not import `feature/ssh` or `feature/webclient`. The SSH implementation also excludes
  Android. These tags mainly remove remaining feature-guarded paths in the current scaffold; they
  do not remove a previously bundled full SSH server or web UI. Keep them to enforce the library's
  intended scope, even though webclient alone slightly increases ZIP size.
- `logtail/logtail_omit.go` makes log upload/logger methods no-ops, and
  `logtail/filch/filch_omit.go` replaces disk buffering with no-ops. The logtail tag also excludes
  conditional network-flow logger registration. Android `go list -deps` loses the same five packages
  on both architectures: `github.com/creachadair/msync/trigger`, `tailscale.com/feature/netlog`,
  `tailscale.com/types/netlogtype`, `tailscale.com/util/truncate`, and `tailscale.com/wgengine/netlog`.
  This import-graph comparison is not a completed linked-binary licence inventory.
- **Startup remains blocked on explicit logging/storage handling.** `tsnet.startLogger` still calls
  `logpolicy.ConfigFromFile` / `NewConfig` / `Save`, creating `tailscaled.log.conf` even with the tag.
  `Server.logf` still defaults to `log.Printf` if `UserLogf` is nil. Set both user/backend loggers to
  discard before Start, and resolve the remaining log-configuration creation (a narrowly scoped
  upstream change may be needed). An in-memory state store does not suppress that file. Do not use
  the upstream test-environment bypass as a production workaround.

Release measurements with identical native sources, module pin, toolchains and linker flags:

| Exclusions | arm64 raw bytes | arm64 ZIP bytes | x86_64 raw bytes | x86_64 ZIP bytes | Complete AAR bytes |
|---|---:|---:|---:|---:|---:|
| None (baseline) | 17,173,456 | 6,673,374 | 18,623,280 | 7,341,106 | 14,020,596 |
| `ts_omit_ssh` | 17,167,856 | 6,672,295 | 18,617,104 | 7,338,199 | 14,016,610 |
| `ts_omit_webclient` | 17,172,240 | 6,674,521 | 18,621,968 | 7,340,510 | 14,021,147 |
| `ts_omit_logtail` | 17,105,616 | 6,649,065 | 18,553,808 | 7,312,462 | 13,967,643 |
| All three (retained) | 17,098,672 | 6,646,573 | 18,546,352 | 7,309,396 | 13,962,085 |

Combined savings: **58,511 bytes (0.417%)** in the complete compressed AAR; **74,784 bytes** in
arm64 and **76,928 bytes** in x86_64 uncompressed. Compression and layout changes make individual
savings non-additive. These are still unstarted-scaffold measurements; repeat when Start/Dial become
reachable. Complete startup/login/DNS/direct-and-relayed-dial tests before treating these exclusions
as verified for a production networking API or publishing a release.

To reproduce an individual measurement, use the opt-in init script (normal builds do not load it):

```sh
./gradlew --no-configuration-cache -I gradle/native-feature-audit.init.gradle \
  -PauditTags=ts_omit_logtail :tsnet:verifyNativeAar
```

Use an empty `-PauditTags=` for baseline, one tag for an individual exclusion, or a comma-separated
list for combinations. Save `tsnet/build/reports/native/sizes.tsv` after each run. The script changes
only release tasks, so **run `./gradlew build` afterwards** to restore the normal feature set.
This iteration's tables, import lists and Go build metadata are retained locally under
`tsnet/build/reports/native-feature-audit/`.

Verification:

- `./gradlew build` passes; repeating it reuses the configuration cache and leaves all four Go builds
  up to date. Both release ABI libraries pass architecture and 16 KiB ELF alignment checks; the release
  AAR excludes the instrumentation bridge. `go version -m` confirms the three exclusions in all four
  libraries and `klethron_test` only in debug.
- Instrumentation JUnit XML counts **five passed tests, zero failures/errors/skips per device** on
  API 34 / x86_64 / 4,096-byte pages and API 37.2 / x86_64 / 16,384-byte pages, both SELinux Enforcing.
  Reports are retained under `tsnet/build/reports/device-verification/feature-exclusions-api34/` and
  `feature-exclusions-api37/`. These cover native lifecycle/version and interface/monitor regression;
  they do not start a node or verify authentication, networking, or arm64 runtime.

## Consumer contracts iteration (2026-11-08)

- Adopt package-level JSpecify `@NullMarked` and pin `org.jspecify:jspecify:1.0.1` as an `api`
  dependency, following [upstream guidance](https://jspecify.dev/docs/using/). This makes reference
  types non-null by default for Java analysis and Kotlin callers. The public version result is the
  first concrete beneficiary. Export the dependency through Maven metadata and ship its licence.
- (Threading annotations were added later; see the control-plane iteration.)
  Defer AndroidX annotations until there is a concrete threading/permission contract: future blocking
  operations can use `@WorkerThread`, and methods opening sockets can use `@RequiresPermission`.
  Do not duplicate JSpecify nullness annotations, blanket-keep the library with `@Keep`, or require
  INTERNET for the current unstarted-node API. `minSdk` already establishes the API 34 floor.
- Enable R8/resource shrinking for the release demo, signed only with the local debug key for tests.
  Build its instrumentation APK in `build`, and exercise the real UI's native lifecycle without direct
  test references to `TsnetNode` or additional JNI keep rules. Two optional AndroidX Test Error Prone
  annotation warnings are suppressed only for the test APK.
- Native sources, dependency versions, feature tags, lifecycle behavior and networking capabilities
  are unchanged.
  This remains an unstarted scaffold, not a functioning tailnet client.

Verification:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passes. Both API/runtime
  Gradle metadata variants and the POM's compile dependency export exactly JSpecify 1.0.1; there is
  no Kotlin stdlib dependency. The published AAR includes `package-info.class` and the JSpecify
  licence; annotation classes remain in their separate transitive artifact. Repeating the build reuses
  the configuration cache and leaves all four Go tasks up to date.
- A local Kotlin 2.4.21 compiler probe against the published AAR's classes accepts normal version
  usage and rejects assigning null to `var version = TsnetNode.tailscaleVersion()`. Removing only
  `package-info.class` makes the same assignment compile, confirming the annotation changes inference.
  Probe artifacts are retained under `tsnet/build/reports/consumer-contracts/`.
- R8 removes all four JSpecify annotation classes and `package-info` from the release demo while
  preserving the JNI class name. No blanket `@Keep` or extra consumer rules were needed.
- Instrumentation JUnit XML records **six passed tests, zero failures/errors/skips per device**:
  five library tests plus one minified demo test on API 34 / x86_64 / 4,096-byte pages and API 37.2 /
  x86_64 / 16,384-byte pages, both SELinux Enforcing. Reports are retained under each module's
  `build/reports/device-verification/consumer-api34/` and `consumer-api37/`. The demo test clicks twice,
  requiring the exact pinned version and successful create/close each time.
- Both release ABI libraries pass architecture/16 KiB ELF alignment checks, and
  `zipalign -c -P 16 4 demo/build/outputs/apk/release/demo-release.apk` passes. These runs establish
  offline native loading/lifecycle through an R8-minified consumer, not Start/login/dial, network
  handover, arm64 runtime, or Play split delivery.

Native sizes are unchanged: arm64-v8a **17,098,672 raw / 6,646,573 ZIP bytes**; x86_64
**18,546,352 raw / 7,309,396 ZIP bytes**. Complete AAR: **13,966,385 bytes**, up **4,300 bytes**
for package metadata and the JSpecify licence. The separately resolved JSpecify JAR is not part
of this AAR size; its classes are removed by R8 in the verified demo.

## Native module notices iteration (2026-11-08)

- Added a checked-in inventory of the **27 Go dependency modules** reported by `go version -m` on
  both ABIs in both variants. Rows record exact module versions, module checksums and notice paths.
  This is more precise than copying the entire `go.mod` graph, but is not a symbol-level inventory.
- Copied 36 upstream licence/patent files verbatim from the verified module cache, including the
  compression library's nested `internal/snapref` and `zstd/internal/xxhash` licences. Existing Go,
  Tailscale and JSpecify notices remain. Each AAR packages 42 notice/inventory files in total.
- `build` now checks both AARs' actual native build metadata against the inventory, rejects module
  replacements and version/checksum drift, verifies the pinned Go version, and compares packaged
  notices byte-for-byte with checked-in files. Verification does not fetch or generate notices.
  Native code, dependency versions, feature tags and public API are unchanged.
- Documented the maintenance procedure and remaining audit scope in `THIRD-PARTY-NOTICES.md`.
  Module metadata does not establish source-level attributions, generated-data provenance or C/NDK
  runtime coverage. The Android import graphs report Tailscale version/toolchain text and DNS fallback
  JSON as embedded files; no resource-retention or complete licence-audit claim follows from that.
  Startup/login/dial and the remaining release prerequisites are still outstanding.

Verification:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passes. Repeating it reuses
  the configuration cache; all four native builds and both notice checks are up to date. Both release
  libraries still pass architecture and 16 KiB alignment checks. `go mod verify` passes, and every
  inventory notice matches its pinned module-cache source byte-for-byte.
- Eleven local fixture checks pass: one valid AAR and ten expected rejections covering missing or
  altered packaged notices, missing source notices, missing/stale modules, version/checksum drift,
  a changed Go runtime, module replacement, and a missing ABI. The runtime/replacement mutations
  target x86_64 after arm64 has passed. Fixtures, logs and the check script are retained locally under
  `tsnet/build/reports/native-notices/`; these are build-tool checks, not JUnit/device tests.
- No JNI/native behavior changed, so device instrumentation was not rerun. No device was attached.
  The preceding iteration's API 34 / 4 KiB and API 37.2 / 16 KiB x86_64 coverage remains the latest;
  there are no new device/JUnit test counts to report. arm64 runtime remains unverified.

Native sizes are unchanged: arm64-v8a **17,098,672 raw / 6,646,573 ZIP bytes**; x86_64
**18,546,352 raw / 7,309,396 ZIP bytes**. Complete release AAR: **14,032,412 bytes**, up
**66,027 bytes (0.47%)** for the module inventory and additional upstream notices.
The R8-minified release demo retains the added assets: **48,689 compressed bytes** plus approximately
**7,574 bytes** of ZIP headers/filenames, roughly **55 KiB** in the APK. Notices therefore contribute
to delivered/installed APK size; the AAR size increase must not be described as having no end-user
storage cost. These are local APK entry measurements, not a Play download or on-device storage test.

## File-free startup iteration (2026-11-08)

- Added a debug-only JNI startup probe, with both user/backend loggers explicitly set to discard
  through the same server constructor as the public unstarted-node API. The probe uses an ephemeral
  in-memory state store, an explicit hostname, and a temporary directory under Android's no-backup
  storage. It refuses ambient authentication configuration. A loopback HTTP stub returns 503 to
  `/key`, preventing control-key exchange and registration. No real auth key or tailnet is involved.
- The probe exercises actual `Start`, repeated `Start`, in-process LocalAPI status (`NeedsLogin`, no
  Tailscale IP), the loopback bootstrap request, and sequential shutdown. It also closes after a
  deliberate startup failure. The test runs off the UI thread and requires the directory to remain
  completely empty after each successful cycle. UTF-8 paths, including a supplementary character,
  cross JNI as explicit byte arrays. Only fixed diagnostic strings are returned, never upstream errors,
  state or login URLs. The release AAR check rejects both instrumentation bridges.
- Patched two upstream logging paths under `!buildfeatures.HasLogTail`. `tsnet.startLogger` returns
  before loading or creating `tailscaled.log.conf`. `ipnlocal.NewLocalBackend` skips socket logger
  initialization, including evaluation of `logpolicy.LogsDir`. The first device run exposed that second
  path: even though `sockstatlog.NewLogger` is itself a no-op, its directory argument tried filesystem
  discovery and panicked on Android. Both guards are needed. No test-environment bypass or global
  environment workaround is used. These patches supersede the logging blockers recorded above.
- `BuildGoLibrary` downloads the pinned module, verifies upstream module checksums, checks exact
  SHA-256 hashes of both original files, and patches an isolated writable build copy. A temporary
  `go.mod` replaces only `tailscale.com` with `./tailscale`; the shared module cache stays unchanged.
  The patch is part of the task implementation fingerprint, so edits/additions/removals invalidate
  Gradle's native tasks, while Go tracks the changed source in its own compilation cache. Shared
  native output caching remains disabled. No dependency or toolchain versions changed.
- Both AARs ship `TAILSCALE-MODIFICATIONS` with original hashes and exact diffs (43 notice/inventory
  files total). The 27-module inventory still records the upstream Tailscale checksum; local replacement
  metadata cannot attest to the patched source with that checksum. Notice verification accepts exactly
  this documented replacement and rejects other replacements or upstream version drift.

Verification:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passes, including alignment
  and notice checks for both ABIs. Repeating it reuses the configuration cache and leaves all four Go
  builds up to date. Upstream source hashes remain unchanged in the shared module cache.
- Instrumentation JUnit XML records **eight passed tests, zero failures/errors/skips per device**:
  seven library tests and one R8-minified demo test, on API 34 / x86_64 / 4,096-byte pages and API 37.2 /
  x86_64 / 16,384-byte pages, both SELinux Enforcing. Each library run includes three successful
  startup/status/close cycles and one deliberate startup failure. Reports are retained under each
  module's `build/reports/device-verification/startup-api34/` and `startup-api37/`.
- A negative control temporarily removed just the `tsnet.startLogger` guard. Gradle `--info` confirmed
  both debug native tasks reran because their implementation classpath changed; the startup test then
  failed because the directory contained one file. Its failure-path companion passed (two JUnit tests,
  one expected failure). The guard was restored and verification repeated. Evidence is retained under
  `tsnet/build/reports/device-verification/startup-negative-api34/`.
- Thirteen local notice fixtures pass: one valid AAR and twelve expected rejections, including an
  unexpected replacement, a changed replacement path and Tailscale version drift. Reports and the
  fixture script are retained under `tsnet/build/reports/native-notices-startup/`.

Release sizes (the public API still cannot start a node):

| Entry | Uncompressed bytes | ZIP-compressed bytes |
|---|---:|---:|
| arm64-v8a library | 17,098,608 | 6,646,303 |
| x86_64 library | 18,546,352 | 7,309,376 |

Complete release AAR: **14,033,202 bytes**, up **790 bytes** from the previous iteration, including the
patch notice. The arm64 library shrank 64 bytes raw; x86_64 raw size is unchanged. Startup is reachable
only in debug, whose libraries are **18,895,568 raw / 7,340,212 ZIP bytes** (arm64) and
**20,504,176 raw / 8,066,693 ZIP bytes** (x86_64); its complete AAR is **15,484,432 bytes**. Do not use
release scaffold sizes to predict a future public networking API's size.

This proves local unauthenticated startup and shutdown, not tailnet connectivity, DNS/TLS bootstrap,
direct/relayed dialing, network changes, arm64 runtime or cancellable public lifecycle. The test checks
its state directory; this is not a syscall-level audit of all upstream filesystem or process behavior.
Persistent node identity remains a future explicit choice using caller-owned no-backup storage; the
patches suppress logging files, not intentional future state persistence.

## Loopback tailnet iteration (2026-11-08)

- Added a debug-only JNI probe that runs a credential-free two-node tailnet inside the test process.
  Upstream `tstest/integration/testcontrol` serves control over loopback HTTP; `derp/derpserver` serves
  one DERP region over loopback TLS; a small responder built on `net/stun` serves STUN. Both nodes use
  the public node constructor (discarding loggers), ephemeral in-memory state, explicit hostnames and
  one shared temporary no-backup directory. `Server.Up` reaches Running and assigns tailnet IPv4.
- The dialer node sends 256 KiB of random data to an echo listener on the other node with `Server.Dial`
  and requires an exact echo. It then waits for an in-process disco ping result and exchanges again:
  - **Direct:** requires a UDP endpoint and no DERP region. The observed endpoint was the
    STUN-reported `127.0.0.1` address, reached in about 32 ms; this is loopback, not cross-device UDP.
  - **Relay only:** sets upstream's `TS_DEBUG_ALWAYS_USE_DERP` envknob before creating nodes, so neither
    node binds UDP. Requires DERP region 1 and no UDP endpoint. The knob is process-global, so the
    probe serializes runs and restores it afterwards. First DERP ping success takes about 6 s.
  DERP's self-signed certificate is skipped through the test-only `InsecureForTests` map flag: this
  exercises DERP framing and relaying, not certificate verification or DNS/TLS bootstrap.
- **The first run found a persistence path.** After nodes reached Running and closed, the node directory
  still contained `profile-data/<id>/netmap-cache/` directories for both nodes (no files remained; whether
  any were written and removed was not established). Upstream writes cached netmaps there whenever
  control grants the `CacheNetworkMaps` capability, and capability version 149 asks control to enable it.
  With a real control server, that would persist peer names, addresses and keys even for ephemeral,
  in-memory nodes. Adopted upstream's
  `ts_omit_cachenetmap` tag in both variants instead of patching; the directory now remains empty.
  The Android import graph is unchanged, because the tag removes code behind a compile-time constant.
  Revisit only if a future persistent-node API has a concrete need for faster cached startup.
- Upstream test servers add five modules to debug libraries only (`axiomhq/hyperloglog`,
  `coder/websocket`, `dgryski/go-metro`, `go4org/hashtriemap`, `kamstrup/intmap`). A debug-only inventory and
  verbatim notices in `src/debug/assets` keep the debug notice check exact at 32 modules. The release
  AAR, its 27-module inventory and its notices are unchanged. `go.mod` gained the corresponding indirect
  requirements; dependency versions in release libraries did not change.
- The release check now rejects any `klethron_*_probe` export or `*Test.nativeProbe` JNI binding. Startup
  and tailnet JNI wrappers share one UTF-8 directory conversion in `node_probes.c`.

Verification:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passes. Repeating it reuses the
  configuration cache with all four Go builds and both notice checks up to date. `go version -m` shows
  all four tags in all four libraries, and `klethron_test` only in debug. Release libraries pass
  architecture/16 KiB alignment checks and contain no test bridge.
- Instrumentation JUnit XML records **ten passed tests, zero failures/errors/skips per device**: nine
  library tests (two new) plus the R8-minified demo test. Devices: dedicated emulators at API 34 / x86_64 /
  4,096-byte pages and API 37.2 / x86_64 / 16,384-byte pages, both SELinux Enforcing. Reports are under
  each module's `build/reports/device-verification/tailnet-api34/` and `tailnet-api37/`.
  The two tailnet tests then passed five further runs per device (20 additional passes, no failures).
- Negative controls: without `ts_omit_cachenetmap`, both tailnet tests failed because the directory
  contained the netmap-cache directories. Leaving the DERP-only knob unset made the relay test fail with
  `unexpected direct path` while the direct test passed (`tailnet-negative-api37/`). Removing one debug
  inventory row made the debug notice check fail with the missing module.

Release sizes with `ts_omit_cachenetmap`, compared with the same sources built without it:

| Entry | Before raw | After raw | Before ZIP | After ZIP |
|---|---:|---:|---:|---:|
| arm64-v8a library | 17,098,608 | 17,048,976 | 6,646,303 | 6,625,669 |
| x86_64 library | 18,546,352 | 18,491,504 | 7,309,376 | 7,287,697 |

Complete release AAR: **13,990,889 bytes**, down **42,313 bytes (0.30%)**. The rebuilt baseline matched the
previous iteration byte-for-byte, so the `go.mod` additions do not affect release. Debug libraries
include both test servers and the tailnet probe. They are **19,920,656 raw / 7,750,665 ZIP bytes**
(arm64) and **21,601,072 / 8,516,871** (x86_64); the debug AAR is **16,351,956 bytes**. Release still
cannot start a node, so do not use these sizes to predict a public networking API.

This proves Android in-process registration, netmap delivery, WireGuard/netstack TCP and DERP relay
between two nodes without credentials. It does not prove a real control plane, DNS/TLS bootstrap,
cross-device or NAT traversal, network changes, arm64 runtime, or a cancellable public lifecycle.

## Android TLS trust roots iteration (2026-11-08)

- **Found:** pinned Go 1.27.3 `crypto/x509` reads Android roots only from `/system/etc/security/cacerts`
  (plus an obsolete `/data/misc/keychain/certs-added`). Since Android 14, the platform's system store
  is the updatable Conscrypt APEX directory `/apex/com.android.conscrypt/cacerts`, so stock Go misses
  roots added or removed by Google Play system updates. It also ignores roots the user disabled in
  Settings (`/data/misc/user/<user>/cacerts-removed`), so the native stack would still trust them.
  Every Tailscale control, DNS-fallback and DERP TLS connection first verifies against this pool.
- **Fix, without patches or environment mutation:** `roots_android.go` loads the first existing
  directory of APEX then system image, drops certificates matching the current user's removed entries
  by DER bytes (as `TrustedCertificateStore` does), and installs the result with
  `x509.SetFallbackRoots`. A `//go:debug x509usefallbackroots=1` directive makes Go use it instead of
  its own on-disk discovery; `go version -m` records it in all four libraries. Setting `SSL_CERT_DIR`
  was rejected because it would mutate the host app's process environment. Installation runs once,
  at first node creation (not library load), before any node can dial. An empty store fails closed.
- **Deliberate limits:** user-added CAs are not trusted, matching Android's default for apps targeting
  API 24+. The consuming app's network security configuration (domain pins, debug overrides, user CA
  opt-in) is not applied to native TLS; document this for consumers. Upstream `tlsdial` still falls
  back to its baked-in Let's Encrypt roots for Tailscale connections, even if a user disabled them
  (since removed by `ts_omit_bakedroots`; see the second feature exclusion iteration).
  The load cost (~120–134 PEM files once per process) was not measured on device.

Verification:

- `./gradlew build` passes; repeating it reuses the configuration cache with all four Go builds up to
  date. Release libraries pass architecture/16 KiB alignment checks, and the existing release check
  rejects the new `klethron_roots_probe` bridge.
- New `TrustRootsTest` compares SHA-256 fingerprints of the Go-loaded roots with Java's
  `AndroidCAStore` `system:` entries, and requires Go's `x509.SystemCertPool()` to equal that pool.
  Instrumentation JUnit XML records **eleven passed tests, zero failures/errors/skips per device**
  (ten library tests plus the R8-minified demo) on dedicated emulators at API 34 / x86_64 / 4,096-byte
  pages (134 roots) and API 37.2 / x86_64 / 16,384-byte pages (120 roots), both SELinux Enforcing.
  Reports are under each module's `build/reports/device-verification/roots-emulator-555{4,6}/`.
- These factory images have identical APEX and system-image directories, so the parity test alone does
  not distinguish stock Go. With a root shell, one APEX root was placed in
  `/data/misc/user/0/cacerts-removed` (owner/mode/SELinux label as Settings would create it). An ordinary
  app could read it, Java excluded the root, and all ten library tests passed on both devices
  (`roots-removed-emulator-555{4,6}/`). Two negative controls on API 37.2 then failed as expected:
  ignoring removals failed fingerprint parity, and skipping `SetFallbackRoots` failed the system-pool
  check (`roots-negative-removed-api37/`, `roots-negative-stock-api37/`). The fixture was removed.
  A diverged Mainline APEX update and a real certificate chain were not exercised; arm64 runtime
  remains unverified.

Release sizes (no dependency or toolchain changes):

| Entry | Before raw | After raw | Before ZIP | After ZIP |
|---|---:|---:|---:|---:|
| arm64-v8a library | 17,048,976 | 17,053,840 | 6,625,669 | 6,630,972 |
| x86_64 library | 18,491,504 | 18,496,688 | 7,287,697 | 7,293,055 |

Complete release AAR: **14,001,550 bytes**, up **10,661 bytes (0.08%)**. Debug AAR: **16,362,948 bytes**.

## Prior art: tailsocks (2026-11-08)

Reviewed [bropines/tailsocks](https://github.com/bropines/tailsocks) (BSD-3-Clause; no code copied). It
runs patched `tailscaled` v1.104.1 as a subprocess behind a foreground Service, SOCKS and VPN modes, so
its architecture conflicts with this library's constraints. It does not address TLS roots. Useful
findings, checked against our pin:

- **Network-change seam:** on each `ConnectivityManager` change it calls
  `netmon.UpdateLastKnownDefaultRouteInterface(linkProperties.interfaceName)` and then
  `Monitor.InjectEvent()`. Without a default interface name, change deltas never mark the network usable,
  so no rebind or netcheck follows. Their polling-wake patch is unnecessary for us: the engine registers
  its monitor in `tsd.System`, reachable via the unstable `tsnet.Server.Sys().NetMon` after Start (pin-test
  it). The route name is process-global. `UpdateLastKnownDefaultGateway` (portmapper) can come from
  `LinkProperties` routes in the same seam. Rebinding/DERP recovery on validation changes is caller policy.
- **Login events:** take the URL from `ipn.Notify.BrowseToURL`, never logs; do not request the
  `Obsolete*` watch-mask bits; clear a cached URL on each backend start. Upstream `printAuthURLLoop` logs it
  through `Server.logf`, which we already discard.
- **DNS:** their hard-coded public fallback resolvers are not needed. tsnet `Dial` falls back to
  `net.DefaultResolver`, and our cgo build uses bionic's resolver (respecting Private DNS). Still verify
  control/DERP bootstrap on a real network.
- **Build tags to measure:** `ts_omit_syspolicy`, `ts_omit_oauthkey`, `ts_omit_drive`,
  `ts_omit_appconnectors`, `ts_omit_c2n`. Keep the portmapper for NAT traversal. (Measured in the second
  feature exclusion iteration: all adopted except `c2n`.)
- Possible later addition: caller-supplied `hostinfo` OS version/device model (they report `linux` by default).

## Default network seam iteration (2026-11-08)

- **Found:** with no host report, pinned Android netmon's default-route interface is always empty, so
  every change delta has `DefaultInterfaceMaybeViable` false and never `RebindLikelyRequired`. Nodes
  would not rebind UDP sockets, re-STUN for a new path, or close DERP connections bound to the old
  network (`maybeCloseDERPsOnRebind` also uses the default interface's addresses) until timeouts.
  The probe confirms the empty default route on both nodes before any report.
- **Added** public static `TsnetNode.setDefaultNetwork(@Nullable String, @Nullable Inet4Address)`. It
  calls upstream's process-global `UpdateLastKnownDefaultGateway` and
  `UpdateLastKnownDefaultRouteInterface`, then `InjectEvent` on the monitor of every started node. A small
  registry (`default_network.go`) records monitors only after `Up`/Start returns, so reports never read
  `Server.Sys()` while startup assigns it; nodes unregister before Close. Updates and registration share
  one lock, and registration injects once to catch a report that raced startup. The public API
  cannot start nodes yet, so in release the registry is empty and the call only sets the values a
  future node will read at startup. Callback registration, `onLost` handling and lifetime stay with the
  caller; the README shows the `ConnectivityManager` pattern. Names longer than `IFNAMSIZ - 1` bytes are
  rejected in Java; JNI copies exact UTF-8 bytes into a fixed buffer, and the gateway crosses as an int.
  IPv6 gateways are not accepted: upstream uses the gateway only for IPv4 NAT port mapping.
- Upstream logs both updates with Go's standard `log.Printf`. The library now discards the standard
  logger once at load (`log.SetOutput(io.Discard)`), extending the existing no-log policy.
- Refactored the loopback tailnet probe into a reusable `loopbackTailnet` (start, exchange, path
  check, ordered close) shared by the existing direct/DERP probe and the new network-change probe.

New `NetworkChangeTest` acts as the consuming app: it starts the loopback two-node tailnet, registers a
default-network callback that forwards to the public API, and requires both nodes to deliver a change
delta with the Wi-Fi interface and `RebindLikelyRequired`, at least two more magicsock rebinds, a
256 KiB exchange and a re-established direct path. It then disables Wi-Fi through the shell, waits for
the cellular interface (`eth0` on these emulators) and requires the same again. It re-enables Wi-Fi and
waits until Wi-Fi is default again; without that wait, every second run started on cellular.

Verification:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passes; repeating `build`
  reuses the configuration cache with all four Go builds up to date. The release libraries export the
  new JNI entry point and no probe bridge; alignment and notice checks pass. No dependency changed.
- Instrumentation JUnit XML records **twelve passed tests, zero failures/errors/skips per device**: eleven
  library tests (one new) plus the R8-minified demo test, on dedicated emulators at API 34 / x86_64 /
  4,096-byte pages and API 37.2 / x86_64 / 16,384-byte pages, both SELinux Enforcing. Reports are under
  each module's `build/reports/device-verification/netchange-api34/` and `netchange-api37/`. The new test
  then passed five further runs per device (`netchange-repeat-*`, 10 passes, about 10–12 s each).
- Negative controls on API 34 failed as expected with `no rebind`: making the Java method a no-op
  (`netchange-negative-api34/`), and keeping the upstream updates but skipping `InjectEvent`
  (`netchange-negative-noinject-api34/`), which shows the ten-minute Android poll is not a substitute.

Release sizes (no dependency or toolchain changes):

| Entry | Before raw | After raw | Before ZIP | After ZIP |
|---|---:|---:|---:|---:|
| arm64-v8a library | 17,053,840 | 17,058,864 | 6,630,972 | 6,631,706 |
| x86_64 library | 18,496,688 | 18,501,808 | 7,293,055 | 7,294,651 |

Complete release AAR: **14,004,430 bytes**, up **2,880 bytes (0.02%)**. Debug AAR: **16,376,122 bytes**.

This proves the seam drives upstream's rebind path on a real Android default-network change. Both
nodes share the emulator's interfaces and talk over loopback, so it does not prove path recovery across
real networks, NAT traversal, DERP reconnection to a remote server, or behavior on hardware/arm64.

## Real control plane and consumer annotations iteration (2026-11-08)

Real control-plane bootstrap (debug-only `control_probe.go`, new `ControlPlaneTest`, needs internet):

- A plain `crypto/tls` dial to `controlplane.tailscale.com:443` resolves through bionic (Go prefers the cgo
  resolver on Android) and verifies only against Go's system pool, i.e. our platform roots. Upstream
  `tlsdial` would silently fall back to baked-in Let's Encrypt roots, so the node's own connection cannot
  prove this (`ts_omit_bakedroots` later removed that fallback). Java requires the verified chain's root SHA-256 to be one of `AndroidCAStore`'s system roots.
- It then starts an ephemeral, in-memory node with discarding loggers and an explicit
  `ipn.DefaultControlURL`. `Start` fetches the control key over TLS, registers over Noise and requests
  interactive login. The probe watches the IPN bus (`NotifyInitialState`) for `BrowseToURL`, requires
  `https://login.tailscale.com/a/...`, then `NeedsLogin` with no Tailscale IP. The URL never leaves Go, so
  the pending registration cannot be authorized; only fixed diagnostics and the public root hash cross JNI.
  The node directory must remain empty. Each run creates one unauthorized pending registration at Tailscale.
- **Found:** `tsnet.Server` reads `TS_CONTROL_URL`, `TS_AUTHKEY`/`TS_AUTH_KEY` and OAuth/ID-token variables
  from the process environment whenever the corresponding field is empty. Probes refuse the auth variables
  and set the control URL explicitly. A public start API must likewise set every field explicitly so the
  host app's environment cannot redirect control or inject credentials.
- Not covered: DERP (control sends the DERP map only after authorization), `dnsfallback` bootstrap DNS
  when system DNS fails, Private DNS modes, proxies, and any authenticated or cross-device path.

Consumer annotations (public API unchanged apart from annotations):

- Measured on emulators (debug libraries): library load **4–5 ms**; first node creation **3 ms** warm,
  **10–20 ms** on a cold page cache (it reads the CA store); later creations **30–70 µs**. Hence
  `TsnetNode()` is `@WorkerThread`; `close`, `tailscaleVersion` and `setDefaultNetwork` are `@AnyThread`
  (synchronized or non-blocking; the last is meant for `ConnectivityManager` callbacks). Revisit `close`
  when a started node's shutdown can block.
- The package is `@NullMarked` and Error Prone `@CheckReturnValue`: a discarded `new TsnetNode()` leaks a
  native node, as there is no finalizer. `@MustBeClosed` was rejected: Klethron holds nodes across leases,
  not in try-with-resources, so every field-held node would need a suppression. No AndroidX
  `@RequiresPermission` yet: no public method opens sockets.
- A scratch consumer (AGP 9.5.1, Kotlin 2.4.21, Error Prone 2.51.0) resolving the published artifact
  showed lint `ThreadConstraint` errors for creation in `onCreate` (Java and Kotlin), lint `CheckResult`
  for an ignored version, Error Prone `CheckReturnValue` for an ignored node/version, and Kotlin
  `-Xreturn-value-checker=check` warnings for both. Results were **identical without `androidx.annotation`,
  `error_prone_annotations` or JSpecify on the consumer classpath**: all tools match names in class files.
  Kotlin still rejected assigning `null` to the version without JSpecify. With none of the three present,
  `javac -Xlint:all -Werror` and an R8 release build passed. Lint does not infer the UI thread for click
  listener lambdas. Probe sources and the lint report are under `tsnet/build/reports/consumer-annotations/`.
- Therefore `androidx.annotation` 1.11.0 and `error_prone_annotations` 2.51.0 (Klethron's pin; 2.52.0 is
  under two weeks old) are `compileOnly`: AndroidX would otherwise export the Kotlin stdlib, and no Error
  Prone classes are distributed, so no new notice is needed. JSpecify stays `api` because
  [upstream](https://jspecify.dev/docs/using/) explicitly recommends against hiding it from users or
  runtime; the published metadata still contains only JSpecify. R8 removes all annotation references from
  the release demo.
- The demo is `@NullMarked`, creates nodes on a background thread and disables its button meanwhile;
  `MinifiedDemoTest` waits for the re-enabled result.

Verification:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passes, including lint, notice
  and release-alignment checks; the release check still rejects the new probe bridge.
- Instrumentation JUnit XML records **thirteen passed tests, zero failures/errors/skips per device**:
  twelve library tests (one new) plus the R8-minified demo test, on dedicated emulators at API 34 / x86_64 /
  4,096-byte pages and API 37.2 / x86_64 / 16,384-byte pages, both SELinux Enforcing (`control-api34/`,
  `control-api37/` under each module's `build/reports/device-verification/`). `ControlPlaneTest` passed
  two further runs per device (`control-repeat-*`) in 1.3–2.6 s per run.
- Negative control on API 34: with no platform root directories the test failed with
  `platform tls dial failed` (`control-negative-noroots-api34/`). `zipalign -c -P 16 4` passes for the
  release demo APK.

Sizes: release native libraries are byte-identical to the previous iteration. Complete release AAR:
**14,004,534 bytes**, up **104 bytes**. Debug libraries are **19,953,296 raw / 7,762,032 ZIP bytes**
(arm64) and **21,634,672 / 8,529,347** (x86_64); the debug AAR is **16,376,460 bytes**.

## Second feature exclusion iteration (2026-11-08)

Measured every omittable upstream feature tag against the unchanged pin, toolchains and sources, on
**debug** x86_64 libraries (Start, Up, Dial and LocalAPI are reachable there, unlike release). Each tag
was added to the four existing exclusions. Kept 14 more, giving 18 in both variants. No upstream patch,
dependency version or toolchain changed. Decisions:

| Omitted feature | Debug x86_64 raw saved | ZIP saved | Reason |
|---|---:|---:|---|
| `debug` | 273,280 | 109,325 | Removes `SSLKEYLOGFILE` TLS key logging (an environment variable would make Go write session keys to a file), control-enabled debug logging and debug endpoints |
| `syspolicy` | 266,336 | 97,115 | No MDM policy store is registered in an embedded Android library |
| `peerapiserver` | 219,424 | 88,451 | The node only connects out; no PeerAPI listener on its tailnet address |
| `useexitnode` | 155,968 | 65,076 | The library dials tailnet and subnet-routed hosts, not the internet via exit nodes |
| `useproxy` | 218,272 | 55,863 | Android's system proxy is not in environment variables; the host's environment must not redirect control/DERP |
| `appconnectors` | 90,624 | 34,026 | The node is never an app connector |
| `usermetrics` | 88,640 | 31,167 | No metrics are served |
| `oauthkey` | 50,432 | 20,253 | Removes the `TS_CLIENT_SECRET` OAuth-to-auth-key path and `golang.org/x/oauth2` |
| `drive` | 37,152 | 14,694 | No Taildrive |
| `exitnodehealth` | 39,040 | 14,262 | Requires `useexitnode` |
| `advertiseroutes` | 14,656 | 4,887 | The node never advertises routes |
| `clientupdate` | 10,208 | 2,644 | Nothing to self-update |
| `bakedroots` | 2,304 | 961 | Trust only Android's system CA store (see below) |
| `advertiseexitnode` | 1,792 | 1,243 | Requires `peerapiserver` and `advertiseroutes` |

Kept, because they are core to a client: `dns` (MagicDNS names, 454 KB raw), `portmapper` (NAT traversal,
395 KB), `health` (status warnings), `gro` (netstack receive throughput), `useroutes`, `tailnetlock`,
`ipnbus` (login URL delivery), `c2n` (control queries; only 23 KB) and `netstack`. Not added despite
compiling: tags saving under about 10 KB without a behavioral reason, and tags with no Android effect
(`cloud` returns early on Android, `portlist` cannot read `/proc/net` from an app, the DNS disk cache's
hooks are never registered by tsnet). `bird`, `netlog` and `taildrop` depend on omitted features but are
not linked, so their tags would be no-ops. Measured but unusable: **`ts_omit_acme` fails to compile
`tsnet` at this pin** (`tsnet.go` calls `local.Client.GetCertificate` unconditionally), so `serve` and
`acme` stay. `clientmetrics` breaks `util/syspolicy`, and `tailnetlock` breaks the debug test servers.
Dependency consistency was checked with upstream `featuretags.RequiredBy`.

**Baked-in roots:** `tlsdial` verified Tailscale control, DERP and DNS-fallback certificates against
Let's Encrypt roots compiled into the binary whenever the platform store rejected them, which bypassed
roots a user disabled in Settings (recorded as a limit in the TLS iteration). With `ts_omit_bakedroots`,
the platform store is the only trust source. Negative controls on API 34 temporarily emptied the
platform store and skipped the probe's plain TLS check. With the tag, the node never got a login URL
(`no login url` after 45 s). Without it, the node reached login through the baked roots and only
failed the later root-hash check (`omit-negative-bakedroots-{omitted,kept}-api34/`). Both changes were
reverted, and `ControlPlaneTest` passed again on the restored build.

Release licence inventory: `golang.org/x/oauth2` is no longer linked, so its row and licence were removed.
The release inventory is **26 modules** and the debug check **31**. `go.mod` keeps the indirect
requirement because the Tailscale module graph needs it; the notice check confirms the linked set.

Verification:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passes, including lint,
  notice and release-alignment/probe checks. Repeating `build` reuses the configuration cache.
  `go version -m` shows all 18 tags in all four libraries and `klethron_test` only in debug.
  `zipalign -c -P 16 4` passes for the release demo APK.
- Instrumentation JUnit XML records **thirteen passed tests, zero failures/errors/skips per device**:
  twelve library tests plus the R8-minified demo test. Devices: dedicated emulators at API 34 / x86_64 /
  4,096-byte pages and API 37.2 / x86_64 / 16,384-byte pages, both SELinux Enforcing (`omit-api34/`,
  `omit-api37/` under each module's `build/reports/device-verification/`). These cover startup, loopback
  direct and DERP-only exchange, network-change rebinding and real control-plane bootstrap with the new
  tags. They do not cover an authorized node, real DERP or MagicDNS resolution of a real peer.

Release sizes (`tsnet/build/reports/native/sizes.tsv`):

| Entry | Before raw | After raw | Before ZIP | After ZIP |
|---|---:|---:|---:|---:|
| arm64-v8a library | 17,058,864 | 15,795,536 | 6,631,706 | 6,170,328 |
| x86_64 library | 18,501,808 | 17,142,544 | 7,294,651 | 6,785,694 |

Complete release AAR: **13,032,988 bytes**, down **971,546 bytes (6.94%)**. Raw libraries shrank
1,263,328 bytes (arm64-v8a, 7.41%) and 1,359,264 bytes (x86_64, 7.35%). Debug libraries are
**18,656,848 raw / 7,293,266 ZIP bytes** (arm64) and **20,241,360 / 8,014,583** (x86_64); the debug AAR is
**15,391,719 bytes**, down 984,741. The table above uses individual debug measurements. Savings are not
additive and release reaches less code than a future public start API will. Measurement script and
results are retained under `tsnet/build/reports/native-feature-audit-2/`.

## Next increments

1. **Prove a real Android node.** Interface discovery, monitor initialization, file-free startup,
   loopback two-node direct/DERP dialing, platform TLS roots, the default-network seam and real
   control-plane DNS/TLS/registration up to `NeedsLogin` are proven above. Remaining: real DERP, fallback
   DNS when system DNS fails, and cross-device or NAT-traversing paths, including recovery after a real
   network change. These need an authorized node: design the explicit live-tailnet proof (browser login
   on a test device, never a checked-in or CI auth key) before adding it.
2. **Add only the lifecycle/configuration needed by that proof.** Explicit no-backup state directory and
   hostname; cancellable start/login/status; deliberate logout. No credential-bearing default log sinks
   or login URLs in logs. Logging files and the netmap cache are removed above; continue auditing
   upstream package initialization, remaining filesystem/process behavior and state permissions.
   `Server.Up` already blocks until Running in the loopback probe; a public start still needs
   cancellation and Close-during-start semantics. Use bounded typed events, not log parsing, to deliver
   the browser login URL after user action; the control probe shows the IPN bus `BrowseToURL` path works.
   Set control URL and auth fields explicitly so tsnet never reads them from the environment. Annotate
   blocking methods `@WorkerThread`, socket-opening ones `@RequiresPermission(INTERNET)`, and keep
   `@CheckReturnValue` (opt out with `@CanIgnoreReturnValue` only where ignoring is harmless).
3. **Design the connection boundary with a real caller.** Opaque connection handles; bounded read/write;
   backpressure, deadlines, cancellation and exactly-once close. Resolve concurrent close/start/dial
   before exposing those APIs. JNI strings need deliberate UTF-8 conversion; current version reporting
   is ASCII only. Keep JNI references, allocation failure and Go panic handling explicit.
4. **Turn the demo into a network proof.** Add INTERNET permission only with networking. Explicit setup,
   browser authorization, one selected destination and a simple exchange. Keep all network work off the
   UI thread. No auth keys checked into tests or supplied to pull requests from forks.
5. **Revalidate feature exclusions with working networking.** Individual and combined measurements for
   all 18 omitted features are recorded above, and the debug device tests exercise them. Reconsider
   `useexitnode` or `useproxy` only if a real caller needs exit nodes or proxies; `acme` cannot be omitted
   until upstream fixes the tsnet build. Confirm MagicDNS peer names still resolve. Once Start/Dial are
   reachable, repeat both ABI libraries' raw/ZIP sizes and the complete AAR comparison against the
   same dependency/toolchain baseline. Verify startup, login, DNS and direct/relayed dialing on API 34
   and 16 KiB devices. Scaffold-only savings and lifecycle tests do not establish the production
   result. Update the licence inventory for the final linked feature set.
6. **Release readiness.** Extend the checked Go module inventory into a complete Android-linked
   dependency/licence review, including per-file attributions, generated data, non-Go embedded resources
   and C/NDK runtime code. Module/root notices and Go runtime notices now ship in both AARs. Test R8-minified
   consumers with actual networking (offline R8 lifecycle now verified), arm64 hardware, API 34,
   16 KiB ELF/APK alignment and cold-start loading. Preserve native symbols separately if needed for
   crash reports. Record sizes per ABI before/after each dependency change.
7. **Publish immutable versions.** Verify ownership of Maven Central namespace `com.klethron`, configure
   signing and protected release credentials, add required POM/source/Javadoc metadata, and publish from
   tags. Keep the wrapper API version independent of upstream tsnet; record the full upstream commit for
   each release. Consumers pin a release and Gradle verification checksums. Never overwrite a release.
8. **Integrate into Klethron separately.** Update its TAILSCALE-PLAN.md/local-build requirement, add the
   artifact only to `:klethron_tailscale`, and test Play split loading plus the existing SSH transport seam.
   Repository creation here does not change that app or its current work in progress.

## Useful commands and maintenance

```sh
./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository
ANDROID_SERIAL=emulator-5554 ./gradlew :tsnet:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :demo:connectedReleaseAndroidTest
./gradlew :tsnet:verifyNativeAar
```

`ControlPlaneTest` needs internet access and contacts Tailscale's public control plane without credentials.
Use an enforcing-mode device for instrumentation (`adb -s <serial> shell getenforce`); a permissive
emulator hides the interface-discovery regression and deliberately fails its negative-control test.

For an intentional upstream update, resolve `tailscale/tailscale` main to a full SHA first, update the
Go requirement and sums, update the version assertion and documented SHA, then rebuild both ABIs and
repeat the device checks. Do not equate an imported `tsnet` package or a successful Close with a
successfully running tailnet node. Current size measurements omit future Start/Dial reachable code.
