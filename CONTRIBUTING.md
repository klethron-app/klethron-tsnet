# Contributing

See [AGENTS.md](AGENTS.md) for conventions and [UPSTREAM.md](UPSTREAM.md) for patches and limitations.

## Build and test

Use the JDK, Go and Android SDK/NDK versions pinned in
[libs.versions.toml](gradle/libs.versions.toml) and [go.mod](tsnet/src/main/go/go.mod). Any JDK 25 or
newer also builds; releases need the pinned one. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
Native builds support Linux x86_64 and macOS; only Linux has been verified.

```sh
./gradlew build
ANDROID_SERIAL=<serial> ./gradlew :tsnet:connectedDebugAndroidTest
ANDROID_SERIAL=<serial> ./gradlew :demo:connectedReleaseAndroidTest
```

CI runs both device suites on x86_64 emulators with API 34/4 KiB pages and API 37.2/16 KiB pages for
main, pull requests and release tags. Its `reports` artifact includes per-device results.

Device tests require SELinux Enforcing. The network-change test requires Wi-Fi with a cellular
fallback and toggles Wi-Fi; use USB ADB on hardware. Debug builds include local control-server and peer
fixtures; release builds exclude them. The demo tests exercise an R8-minified consumer.

The AAR is `tsnet/build/outputs/aar/tsnet-release.aar`; native sizes are in
`tsnet/build/reports/native/sizes.tsv`, and `verifyNativeAar` checks their 16 KiB alignment.

For a live check, install `:demo:installRelease` and open **Live tailnet test**. Authorize the node in
your own tailnet, return to the app and wait for **RUNNING / Login saved**. Test TCP echo against a peer
you control, by IP and MagicDNS name; then check restart, logout/re-login, key expiry, device removal
and Wi-Fi/cellular transitions. Verify direct or DERP paths from the peer's status. Record outcomes
and timings, never login URLs or node state. Remove test nodes afterwards. The demo has no service;
background suspension can delay login and traffic.

## Use a checkout in another app

Add this to the app's `settings.gradle.kts`:

```kotlin
providers.gradleProperty("tsnet.checkout").orNull?.let { includeBuild(it) }
```

Build with `-Ptsnet.checkout=../klethron-tsnet`. Gradle substitutes the checkout for `com.klethron:tsnet`
and selects the matching variant. Both projects need the same Android Gradle plugin version, plus the
native toolchains above.

To test the release AAR instead, run `./gradlew :tsnet:publishReleasePublicationToLocalBuildRepository`.
Add `maven("../klethron-tsnet/build/maven") { content { includeGroup("com.klethron") } }` to the app's
repositories and depend on the version in [gradle.properties](gradle.properties).

## Publish

1. Complete the build, device tests and licence inventory. Commit `version=X.Y.Z` in
   `gradle.properties` and push tag `vX.Y.Z` on that commit.
2. CI builds the publication and waits for approval in the protected `maven-central` environment.
   Download the run's `maven-release` artifact (`gh run download <run> -n maven-release`) and compare it
   with a local build of the tag using the pinned toolchains. POMs and native libraries must be
   byte-identical, and archives must contain identical files. Compressed bytes can differ between
   JDK distributions; use the pinned JDK patch version for reproducibility.
3. Approve the release job. It signs and publishes the prebuilt files to Maven Central; publication is
   irreversible. Commit the next `-SNAPSHOT` version afterwards.

The environment requires a reviewer, `v*` tags only, and secrets `SIGNING_KEY` (the signing subkey from
`gpg --export-secret-subkeys`), `SIGNING_PASSPHRASE`, `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` (a Central
Portal token). The build job receives no secrets. [central-bundle.sh](.github/central-bundle.sh) verifies
all signatures against [release-signing-key.asc](release-signing-key.asc).

The release key fingerprint is `361C CF78 F83A DAED D8DC 6DA8 A32D AC0A CDC6 D35B`; the signing subkey is
`2F78 F885 F811 292A 6199 2A11 8C31 C50B B472 B529` (expires 2028-11-08). Keep the primary key offline.
Extend the subkey's expiry rather than replacing it, so its fingerprint stays valid for verifiers;
commit the updated public key and upload it to `keys.openpgp.org` and `keyserver.ubuntu.com`.
