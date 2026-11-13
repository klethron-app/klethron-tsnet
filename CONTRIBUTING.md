# Contributing

Small fixes, documentation changes and bug reports are welcome. See [AGENTS.md](AGENTS.md) for
conventions and [UPSTREAM.md](UPSTREAM.md) for patches and limitations.

## Pull requests

Fork the repository, make a branch and open a PR against `main`. Describe the problem, your change
and what you tested. If you cannot run the build or device tests locally, say so; CI runs both.
The automated tests use local fixtures and need no Tailscale account or repository secrets.

First-time contributors may need a maintainer to approve the CI run. If GitHub shows **Awaiting
approval**, there is nothing you need to configure in your fork. Further pushes update the same PR
and cancel its older CI run.

Open a failed check's **Details** to find the failing step. The run summary shows Android test counts;
download its `reports-api-*` artifact for HTML reports and JUnit XML. CI checks whitespace, shell scripts,
workflows and Go formatting, then builds and tests API 34/4 KiB and API 37.2/16 KiB x86_64 emulators
on separate runners. Each job builds independently, and both must pass before a release can proceed.
For Go changes, run `gofmt -w tsnet/src/main/go`; `git diff --check` catches whitespace errors.

## Build and test

Install JDK 25 or newer, the Go version in [go.mod](tsnet/src/main/go/go.mod), and the Android SDK
platform, build tools and NDK pinned in [libs.versions.toml](gradle/libs.versions.toml).
CI and releases use the pinned JDK. Set `ANDROID_HOME` or `sdk.dir` in `local.properties` to your SDK.
Native builds support Linux x86_64 and macOS; only Linux has been verified.

```sh
./gradlew build
ANDROID_SERIAL=<serial> ./gradlew :tsnet:connectedDebugAndroidTest
ANDROID_SERIAL=<serial> ./gradlew :demo:connectedReleaseAndroidTest
```

Device tests require SELinux Enforcing and internet access. The network-change test toggles Wi-Fi and
needs cellular fallback; use USB ADB on hardware. Debug builds include local control-server and peer
fixtures. Demo tests exercise an R8-minified release consumer.

The AAR is `tsnet/build/outputs/aar/tsnet-release.aar`. `verifyNativeAar` checks 16 KiB alignment and
writes sizes to `tsnet/build/reports/native/sizes.tsv`.

For a live check, install and open the demo (`:demo:installRelease`). Authorize the node in your
tailnet and wait for **RUNNING / Login saved**. Echo against a peer you control, such as one running
`socat TCP-LISTEN:7070,fork,reuseaddr EXEC:cat`, by IP and MagicDNS name; then check restart,
logout/re-login, key expiry, device removal, Wi-Fi/cellular switches and the direct or DERP path in the
peer's status. Never record login URLs or node state; remove test nodes afterwards. The demo has no
service, so background suspension can delay login and traffic. `:demo:connectedReleaseAndroidTest`
uninstalls the demo, discarding its login.

## Use a checkout in another app

Add this to the app's `settings.gradle.kts`:

```kotlin
providers.gradleProperty("tsnet.checkout").orNull?.let { includeBuild(it) }
```

Build with `-Ptsnet.checkout=../klethron-tsnet` to substitute the matching variant for `com.klethron:tsnet`.
Both projects need the same Android Gradle plugin version, plus the native toolchains above.

To test the release AAR instead, run `./gradlew :tsnet:publishReleasePublicationToLocalBuildRepository`.
Add `maven("../klethron-tsnet/build/maven") { content { includeGroup("com.klethron") } }` to the app's
repositories and depend on the version in [gradle.properties](gradle.properties).

## Publish

1. Complete the build, device tests and licence inventory. Commit `version=X.Y.Z` in
   `gradle.properties` and push tag `vX.Y.Z` on that commit.
2. CI builds and tests the tagged commit, then waits for approval in the protected `maven-central`
   environment.
3. Approve the release job. It signs and publishes the prebuilt files to Maven Central, which is
   irreversible, then creates the GitHub release. Commit the next `-SNAPSHOT` version afterwards.

A separate `javadoc` job then imports the published version into javadoc.io, with attempts at 5-minute
intervals from minute 5 through minute 30. It stops once that version's documentation is available.
If it fails, rerun the failed job to retry the import without publishing again.

The environment requires a reviewer, `v*` tags only, and secrets `SIGNING_KEY` (from
`gpg --export-secret-subkeys`), `SIGNING_PASSPHRASE`, `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` (a Central
Portal token). The build job receives no secrets. [central-bundle.sh](.github/central-bundle.sh)
verifies signatures against [release-signing-key.asc](release-signing-key.asc).

The release key fingerprint is `361C CF78 F83A DAED D8DC 6DA8 A32D AC0A CDC6 D35B`; the signing subkey is
`2F78 F885 F811 292A 6199 2A11 8C31 C50B B472 B529` (expires 2028-11-08). Keep the primary key offline.
Extend the subkey's expiry rather than replacing it, so its fingerprint stays valid for verifiers;
commit the updated public key and upload it to `keys.openpgp.org` and `keyserver.ubuntu.com`.
