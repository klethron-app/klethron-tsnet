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

## Scaffold checkpoint

Tailscale main is pinned to `503010831ecd44ab3d86864e5e65ff9f8b969d88` (2026-11-07 UTC), resolved
2026-11-08. The Go module pin is `v1.105.0-pre.0.20261107202901-503010831ecd`.
Go 1.27.3, NDK 30.0.16248370, AGP 9.5.1 and Gradle 9.8.2 are pinned.

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

Verified locally on 2026-11-08:

- `./gradlew build :tsnet:publishReleasePublicationToLocalBuildRepository` passed. Repeating the command
  reused the configuration cache and left both Go compilation tasks up to date. The generated POM has
  the intended coordinates and no Java runtime dependencies (implicit Kotlin stdlib is disabled).
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

## Next increments

1. **Prove a real Android node.** Inspect the pinned upstream's Android interface discovery and network
   monitor support. Investigate [upstream issue 17311](https://github.com/tailscale/tailscale/issues/17311)
   against this exact revision rather than applying an old workaround blindly. Prove startup and direct
   and relayed TCP dialing on Android 14 and the current 16 KiB emulator before promising an SDK.
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

For an intentional upstream update, resolve `tailscale/tailscale` main to a full SHA first, update the
Go requirement and sums, update the version assertion and documented SHA, then rebuild both ABIs and
repeat the device checks. Do not equate an imported `tsnet` package or a successful Close with a
successfully running tailnet node. Current size measurements omit future Start/Dial reachable code.
