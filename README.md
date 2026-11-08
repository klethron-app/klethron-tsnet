# Klethron tsnet

An Android AAR containing a precompiled Tailscale tsnet engine and a small Java/JNI bridge.
Java package: `com.klethron.tsnet`. Maven coordinates: `com.klethron:tsnet:0.1.0-SNAPSHOT`.

**Initial scaffold:** native loading, linked-version reporting, and creating/closing an unstarted node.
Android interface discovery and network-monitor initialization are now verified under app restrictions.
Debug-only tests also start a node against a rejecting loopback control endpoint, and connect two
in-process nodes through a loopback test control and DERP server to exchange TCP data over direct
and relay-only paths. These nodes write no files. Public startup, login, peer discovery and dialing
are not implemented, and no real tailnet has been used. No package registry release exists yet.
See [PLAN.md](PLAN.md) for the next implementation steps and verification status.

## Build

Requires JDK 25, Go 1.27.3, an Android SDK configured through `ANDROID_HOME` or `local.properties`,
SDK platform `android-37.2`, build-tools `37.0.0`, and NDK `30.0.16248370`.
Native builds support Linux x86_64 and macOS with the NDK's Darwin host tools; only Linux is verified.

```sh
./gradlew build
./gradlew :tsnet:publishReleasePublicationToLocalBuildRepository
ANDROID_SERIAL=emulator-5554 ./gradlew :tsnet:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :demo:connectedReleaseAndroidTest
./gradlew :demo:installDebug
```

The release AAR is `tsnet/build/outputs/aar/tsnet-release.aar`. The local Maven repository is `build/maven/`.
The native size/alignment report is `tsnet/build/reports/native/sizes.tsv`.
Android 14/API 34 is the minimum; the AAR contains arm64-v8a and x86_64 native code.
Consumers of a published AAR will not need Go or an NDK.

The Java API uses package-level JSpecify `@NullMarked`: reference types are non-null unless explicitly
annotated otherwise. Kotlin consumers see `tailscaleVersion()` as `String`, and Java nullness tools can
check the same contract. JSpecify 1.0.1 is a transitive API dependency in the Maven publication; use the
Maven coordinates to retain dependency metadata. Copying only the AAR requires supplying JSpecify too.
See [JSpecify's dependency guidance](https://jspecify.dev/docs/using/).

AndroidX threading and permission annotations are deferred until the corresponding networking APIs
exist. The current unstarted-node API needs no INTERNET permission. Callers must close nodes explicitly;
`close()` is idempotent and synchronized. Nullness annotations do not enforce resource ownership.

Both build variants disable Tailscale SSH, the embedded web client, logtail and the on-disk netmap
cache using upstream build tags. The netmap cache would otherwise persist peer names, addresses and
keys in the node directory whenever the control server enables it. Two small patches also skip upstream log-configuration creation and socket-log directory lookup
when logtail is disabled. Both user and backend loggers discard output. Patches are applied to an
isolated, verified build copy; the shared module cache is untouched. The AAR ships the patch notice.
The patch implementation is part of Gradle's task fingerprint, so edits invalidate native builds;
Go's cache also tracks the modified source. Shared native output caching is disabled.
See [PLAN.md](PLAN.md) for verification and remaining startup work.

Native TLS verification uses Android's current system CA store: the updatable Conscrypt APEX directory
on Android 14+, without roots the user disabled in Settings. Stock Go would read only the system image
copy, missing root changes delivered by Google Play system updates. User-added CAs and the consuming
app's network security configuration do not apply to native TLS. For its own control and DERP
servers, Tailscale also accepts its built-in Let's Encrypt roots.

The engine runs inside the Android app process using Go goroutines and native threads. The library
does not launch executables or subprocesses. Future blocking networking APIs must be called off the
UI thread; construction and close are currently synchronous.

The demo has one button that loads the library and creates/closes a node. It has no network permission,
stores no identity and does not authenticate to Tailscale. JNI instrumentation tests interface discovery,
network monitors and unauthenticated startup, using INTERNET permission only in the test APK. The
startup test uses an in-memory identity store and a temporary no-backup directory, requires that the
directory remain empty, and contacts its own loopback control stub, which refuses key exchange.
The tailnet test runs Tailscale's own test control server and a DERP/STUN server in the test process on
127.0.0.1. Two ephemeral in-memory nodes register without credentials, then exchange 256 KiB of TCP
data twice: once over a verified direct UDP path, and once with UDP disabled so DERP is the only path.
DERP uses a self-signed certificate that clients are told not to verify. These test servers link five
more Go modules into debug libraries only; their notices ship in the debug AAR.
It requires a device with SELinux **Enforcing** (`adb shell getenforce`) to verify the standard Go
discovery failure. Native test bridges are present only in debug builds; the release AAR check rejects
them. Neither the demo nor these tests proves connectivity to a real tailnet, DNS/TLS bootstrap, or a
public dial API.

The release demo enables R8 and resource shrinking and uses the local debug signing key solely for
installation/testing. `build` assembles it and its instrumentation APK; `:demo:connectedReleaseAndroidTest`
exercises the actual button through native load, version reporting and repeated create/close. Its test
does not reference `TsnetNode` or add JNI keep rules. The demo uses the standard optimized Android
rules and the AAR's consumer rules.

## Upstream pin

Tailscale `main` resolved on 2026-11-08 to
[`503010831ecd44ab3d86864e5e65ff9f8b969d88`](https://github.com/tailscale/tailscale/commit/503010831ecd44ab3d86864e5e65ff9f8b969d88).
`tsnet/src/main/go/go.mod` pins its Go pseudo-version; `go.sum` verifies downloaded module content.
Normal builds use `-mod=readonly`, never a moving branch or `@latest`.

## Distribution

The source repository is public. Maven Central is the intended release destination, pending namespace
verification and release setup. GitHub Packages is an alternative, but requires authentication even
for public downloads. CI retains verification reports; it does not publish binary artifacts or Maven releases.

Original wrapper/build/demo code uses the Apache License 2.0. Tailscale and its dependencies retain
their own licences. Both AAR variants include an inventory and notices for the 27 Go modules recorded
in their native binaries. `build` checks module versions/checksums and packaged notice contents for
both ABIs. Source-level, embedded-resource and native toolchain attribution review remains a release
prerequisite. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
