# Klethron tsnet

An Android AAR containing a precompiled Tailscale tsnet engine and a small Java/JNI bridge.
Java package: `com.klethron.tsnet`. Maven coordinates: `com.klethron:tsnet:0.1.0-SNAPSHOT`.

**Initial scaffold:** native loading, linked-version reporting, and creating/closing an unstarted node.
Android interface discovery and network-monitor initialization are now verified under app restrictions.
Login, starting a node, peer discovery and dialing are not implemented. No package registry release exists yet.
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

Both build variants disable Tailscale SSH, the embedded web client, and logtail using upstream build
tags. This disables log uploads and disk log buffering, but does not yet make node startup safe:
upstream still creates log configuration and defaults to a user logger. See the measured comparisons
and remaining startup work in [PLAN.md](PLAN.md).

The demo has one button that loads the library and creates/closes a node. It has no network permission,
stores no identity and does not authenticate to Tailscale. JNI instrumentation also tests interface
discovery and network-monitor creation, using INTERNET permission only in the test APK to open sockets.
It contacts no endpoint and requires a device with SELinux **Enforcing** (`adb shell getenforce`) to
verify the standard Go discovery failure. The native test bridge is present only in debug builds;
the release AAR check rejects it. Neither the demo nor these tests proves tailnet connectivity.

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
