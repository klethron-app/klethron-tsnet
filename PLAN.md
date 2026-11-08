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
- `check` checks Android lint and native AAR architecture/alignment, and compiles JNI instrumentation.
- `:demo` is a platform-only Java Activity with an explicit offline native-load/lifecycle button.
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

## Next increments

1. **Prove a real Android node.** Interface discovery and monitor initialization are addressed above.
   Next prove actual startup with controlled storage/logging, then direct and relayed TCP dialing on
   Android 14 and the current 16 KiB emulator. Include DNS/TLS bootstrap and network changes; a working
   interface getter does not prove these. Keep tests credential-free until an explicit live-tailnet proof.
2. **Add only the lifecycle/configuration needed by that proof.** Explicit no-backup state directory and
   hostname; cancellable start/login/status; deliberate logout. No credential-bearing default log sinks
   or login URLs in logs. Audit upstream package initialization, logtail and state permissions. Use
   bounded typed events, not log parsing, to deliver the browser login URL after user action.
3. **Design the connection boundary with a real caller.** Opaque connection handles; bounded read/write;
   backpressure, deadlines, cancellation and exactly-once close. Resolve concurrent close/start/dial
   before exposing those APIs. JNI strings need deliberate UTF-8 conversion; current version reporting
   is ASCII only. Keep JNI references, allocation failure and Go panic handling explicit.
4. **Turn the demo into a network proof.** Add INTERNET permission only with networking. Explicit setup,
   browser authorization, one selected destination and a simple exchange. Keep all network work off the
   UI thread. No auth keys checked into tests or supplied to pull requests from forks.
5. **Release readiness.** Generate an exact Android-linked dependency/licence inventory, including any
   non-Go embedded resources and Go runtime notices. Package notices in the AAR. Test R8-minified
   consumers, arm64 hardware, API 34, 16 KiB ELF/APK alignment and cold-start loading. Preserve native
   symbols separately if needed for crash reports. Record sizes per ABI before/after each dependency change.
6. **Publish immutable versions.** Verify ownership of Maven Central namespace `com.klethron`, configure
   signing and protected release credentials, add required POM/source/Javadoc metadata, and publish from
   tags. Keep the wrapper API version independent of upstream tsnet; record the full upstream commit for
   each release. Consumers pin a release and Gradle verification checksums. Never overwrite a release.
7. **Integrate into Klethron separately.** Update its TAILSCALE-PLAN.md/local-build requirement, add the
   artifact only to `:klethron_tailscale`, and test Play split loading plus the existing SSH transport seam.
   Repository creation here does not change that app or its current work in progress.

## Useful commands and maintenance

```sh
./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository
ANDROID_SERIAL=emulator-5554 ./gradlew :tsnet:connectedDebugAndroidTest
./gradlew :tsnet:verifyNativeAar
```

Use an enforcing-mode device for instrumentation (`adb -s <serial> shell getenforce`); a permissive
emulator hides the interface-discovery regression and deliberately fails its negative-control test.

For an intentional upstream update, resolve `tailscale/tailscale` main to a full SHA first, update the
Go requirement and sums, update the version assertion and documented SHA, then rebuild both ABIs and
repeat the device checks. Do not equate an imported `tsnet` package or a successful Close with a
successfully running tailnet node. Current size measurements omit future Start/Dial reachable code.
