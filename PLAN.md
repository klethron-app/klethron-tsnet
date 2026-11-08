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
- Both variants use `ts_omit_ssh`, `ts_omit_webclient`, `ts_omit_logtail` and `ts_omit_cachenetmap`.
  These disable out-of-scope features, logtail uploads/buffering and the on-disk netmap (peer) cache.
  Two local patches skip log configuration and socket-log directory initialization; both user/backend
  loggers discard output.
- `check` checks Android lint, native AAR architecture/alignment and both variants' Go module notices,
  and compiles JNI instrumentation.
- `:demo` is a platform-only Java Activity with an explicit offline native-load/lifecycle button.
- Public Java types use JSpecify non-null defaults, exported with the Maven dependency metadata.
  The release demo exercises the native lifecycle with R8/resource shrinking enabled.
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
- Defer AndroidX annotations until there is a concrete threading/permission contract: future blocking
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

## Next increments

1. **Prove a real Android node.** Interface discovery, monitor initialization, file-free startup and
   loopback two-node direct/DERP dialing are proven above. Remaining: DNS/TLS bootstrap with real
   certificate verification, a host notification seam for network changes (with a test that changes
   the default network), and cross-device or NAT-traversing paths. Keep tests credential-free until an
   explicit live-tailnet proof; the loopback harness can host the network-change test.
2. **Add only the lifecycle/configuration needed by that proof.** Explicit no-backup state directory and
   hostname; cancellable start/login/status; deliberate logout. No credential-bearing default log sinks
   or login URLs in logs. Logging files and the netmap cache are removed above; continue auditing
   upstream package initialization, remaining filesystem/process behavior and state permissions.
   `Server.Up` already blocks until Running in the loopback probe; a public start still needs
   cancellation and Close-during-start semantics. Use
   bounded typed events, not log parsing, to deliver the browser login URL after user action.
3. **Design the connection boundary with a real caller.** Opaque connection handles; bounded read/write;
   backpressure, deadlines, cancellation and exactly-once close. Resolve concurrent close/start/dial
   before exposing those APIs. JNI strings need deliberate UTF-8 conversion; current version reporting
   is ASCII only. Keep JNI references, allocation failure and Go panic handling explicit.
4. **Turn the demo into a network proof.** Add INTERNET permission only with networking. Explicit setup,
   browser authorization, one selected destination and a simple exchange. Keep all network work off the
   UI thread. No auth keys checked into tests or supplied to pull requests from forks.
5. **Revalidate feature exclusions with working networking.** Initial individual/combined measurements
   for `ts_omit_ssh`, `ts_omit_webclient`, `ts_omit_logtail` and `ts_omit_cachenetmap` are recorded above.
   The loopback tailnet test now exercises all four in debug. Once Start/Dial are
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

Use an enforcing-mode device for instrumentation (`adb -s <serial> shell getenforce`); a permissive
emulator hides the interface-discovery regression and deliberately fails its negative-control test.

For an intentional upstream update, resolve `tailscale/tailscale` main to a full SHA first, update the
Go requirement and sums, update the version assertion and documented SHA, then rebuild both ABIs and
repeat the device checks. Do not equate an imported `tsnet` package or a successful Close with a
successfully running tailnet node. Current size measurements omit future Start/Dial reachable code.
