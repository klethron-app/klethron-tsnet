# Klethron tsnet

An Android AAR containing a precompiled Tailscale tsnet engine and a small Java/JNI bridge, so an app can
join a tailnet from its own process (no VPN, service or subprocess) and open TCP connections to peers.
Java package `com.klethron.tsnet`, Maven coordinates `com.klethron:tsnet:0.1.0`.

**Early development:** login, status, dialing by address or MagicDNS name, logout and revocation are
verified against loopback test servers and, on arm64 hardware, a real tailnet. No release is published
yet. See [PLAN.md](PLAN.md) for verification status and next steps.

## Build

Requires JDK 25 (25.0.5 for byte-identical output), Go 1.27.3, an Android SDK (`ANDROID_HOME` or `local.properties`) with platform
`android-37.2`, build-tools `37.0.0` and NDK `30.0.16248370`. Native builds support Linux x86_64 and
macOS hosts; only Linux is verified.

```sh
./gradlew build
./gradlew :tsnet:publishReleasePublicationToLocalBuildRepository   # to build/maven/
ANDROID_SERIAL=<serial> ./gradlew :tsnet:connectedDebugAndroidTest
ANDROID_SERIAL=<serial> ./gradlew :demo:connectedReleaseAndroidTest
```

The release AAR is `tsnet/build/outputs/aar/tsnet-release.aar`; native sizes and alignment are in
`tsnet/build/reports/native/sizes.tsv`. It contains arm64-v8a and x86_64 code, needs API 34, and
consumers need neither Go nor an NDK.

## Use

```java
// On a background thread. The directory holds the node's private keys: keep it out of backups.
try (TsnetNode node = new TsnetNode()) {
    node.start(new File(context.getNoBackupFilesDir(), "tailscale"), "my-device");
    TsnetStatus status = null;
    do {
        status = node.awaitStatus(status, Duration.ofSeconds(30));
        if (status.loginUrl() != null) {
            // Open in the user's browser; never log it. Whoever opens it can claim this node.
        }
    } while (status.state() != TsnetStatus.State.RUNNING);
    // Closing the node disconnects it; its state remains for the next start.
    try (ParcelFileDescriptor connection = node.dial("my-server", 22, Duration.ofSeconds(30))) {
        // A connected stream socket: use FileInputStream/FileOutputStream, or android.system.Os.
    }
}
```

The Javadoc of `TsnetNode` and `TsnetStatus` specifies each call. In short:

- `dial` reaches only peers and subnet routes in the network map, never the device's own network. The
  result is one end of a Unix socket pair relayed to the tailnet connection, so it can be polled.
- `logout`, then `login`, re-authenticates; `TsnetStatus.revocation()` says whether control expired the
  key or removed the device. Close a node only after its login URL clears, or the login may be lost.
- `TsnetNode.setDefaultNetwork(interfaceName, ipv4Gateway)` must be fed from a
  `ConnectivityManager.registerDefaultNetworkCallback` (`onLinkPropertiesChanged` and `onLost`): native
  code cannot see the default network on Android, and without it nodes never rebind after a change.
- Every connection setting is explicit. `start` ignores `TS_CONTROL_URL` and refuses to run if the
  environment sets `TS_AUTHKEY` or `TS_AUTH_KEY`. Only `tailscaled.state` (0600, directory 0700) is
  written; logs are discarded.
- Native TLS trusts Android's current system CA store (the Conscrypt APEX, minus roots the user
  disabled), not user-added CAs, the app's network security configuration or Tailscale's baked-in roots.
- A Go bug ends the whole process. A panic is a native crash (`ApplicationExitInfo.REASON_CRASH_NATIVE`)
  whose only logcat output (tag `Go`) is the panic message; a Go fatal error exits with status 2
  (`REASON_EXIT_SELF`) and leaves no tombstone. Android starts apps in a mode in which Go prints no
  tracebacks, and native backtraces stop in the Go runtime.

The package is JSpecify `@NullMarked` (JSpecify is a transitive `api` dependency; copying only the AAR
requires adding it) and Error Prone `@CheckReturnValue`. Methods carry AndroidX thread annotations
(`@WorkerThread`, except `@AnyThread` `tailscaleVersion` and `setDefaultNetwork`) and `start` is
`@RequiresPermission(INTERNET)`. Lint, Error Prone and Kotlin's `-Xreturn-value-checker` read these by
name, so the AAR does not depend on `androidx.annotation` or `error_prone_annotations`. Lint does not
check the constructor or try-with-resources' implicit `close()`.

Both variants omit 19 upstream features with build tags (`tsnet/build.gradle.kts`), including SSH, the web
client, ACME certificates, logtail, debug hooks, PeerAPI, exit nodes, environment proxies, OAuth secrets, MDM policy and the
on-disk netmap cache. Two upstream patches keep the login after a failed logout and stop a netmap in
flight from undoing a logout; see [UPSTREAM.md](UPSTREAM.md).

## Tests

Instrumentation tests need an SELinux **Enforcing** device (`adb shell getenforce`). Native test bridges
exist only in debug builds; `verifyNativeAar` rejects them in release.

- `LoginTest` and `DialTest` drive the public API against an in-process loopback test control server:
  interactive login, restart from state, logout, failed logout, key expiry, device removal, device
  approval, rejected registration, and dialing an in-memory peer by address and MagicDNS name,
  blocking and non-blocking, with backpressure in both directions.
- `TailnetTest` exchanges data between two in-process nodes over a direct path and DERP only.
  `NetworkChangeTest` disables Wi-Fi and requires both nodes to rebind; on hardware, use USB ADB.
- `NetworkInterfacesTest` and `TrustRootsTest` compare native interfaces and CA roots with Android's.
- `CrashTest` fails fatally in Go in a separate process and checks how Android records it and that
  logcat gets no traceback.
- `ControlPlaneTest` (needs internet) registers with Tailscale's real control plane until it offers a
  login URL, which never leaves native code.
- The R8-minified release demo's tests load the library and start its live screen until browser
  authorization is offered, without opening it, then require a rotation to keep the screen and node.

## Live tailnet test

The demo's opt-in **Live tailnet test** screen exercises the API on a real tailnet. Use a tailnet and peer
you own; never use an auth key.

1. `./gradlew :demo:installRelease`, then open **Klethron tsnet demo → Live tailnet test → Start / resume node**.
2. Tap **Authorize in browser**, approve the node, return and wait for **RUNNING / Login saved**. Android
   freezes the demo in the background, so the login completes only after you return. Do not close while
   authorization is pending. Never copy the URL or node state into reports.
3. On another peer, allow TCP 7070 from the node and run this echo server with that peer's Tailscale IPv4
   address:

   ```sh
   python3 - 100.x.y.z <<'PY'
   import socketserver
   import sys

   class Echo(socketserver.BaseRequestHandler):
       def handle(self):
           self.request.settimeout(15)
           while data := self.request.recv(4096):
               self.request.sendall(data)

   with socketserver.ThreadingTCPServer((sys.argv[1], 7070), Echo) as server:
       server.serve_forever()
   PY
   ```

4. Enter the peer's address and port 7070 and tap **Verify TCP echo**: expect **Echo verified: 52 B**.
   Repeat with its MagicDNS name. **Keep connection open** reuses one stream, for network-change tests.
5. **Close node**, reopen and Start: expect RUNNING without a browser, and echo again. **Log out of
   tailnet**: echoes must now fail. **Log in / renew key**, authorize and echo again.

Record only outcomes, device/API/ABI/page size, network types and timings:

| Case | Evidence required |
|---|---|
| Logout, admin key expiry, removal | Access ends; state and `revocation()`; re-login restores the echo. Key expiry needs the API (`POST /api/v2/device/{id}/expire` with a short-lived token); removal is in the admin console |
| Wi-Fi ↔ cellular | Fresh and kept-open echoes succeed after each switch; the peer sees one unchanged connection (`ss -tn`) |
| Direct or DERP path | The peer's own status during the exchange; an echo alone does not show the path. Force DERP with an ephemeral tsnet echo peer run with `TS_DEBUG_ALWAYS_USE_DERP=true` and log it out afterwards |
| Fallback DNS | System DNS fails (strict Private DNS set to an unresolvable host; delete both settings afterwards) while a fresh process starts and connects |

Stop the echo server and log out or remove the test node when finished. The demo has no service: the node
lives while its screen exists, and uninstalling does not remove the machine from the tailnet.

## Distribution

Maven Central is the intended destination, pending namespace verification and release setup; nothing is
published yet. Original code is Apache-2.0; Tailscale and its dependencies keep their own licences, with
notices in the AAR (see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)). Tailscale is pinned to
[`62d055f4c38b`](https://github.com/tailscale/tailscale/commit/62d055f4c38b610d63a8cee7fa4a7e65b01564eb)
in `tsnet/src/main/go/go.mod`; builds use `-mod=readonly`, never a moving branch.

A release is the commit of its tag, which the POM names (`<scm><tag>`); that commit pins Tailscale, Go and
the NDK, and `TsnetNode.tailscaleVersion()` reports the linked Tailscale version. To release `X.Y.Z`:

1. Commit `version=X.Y.Z` in `gradle.properties`, then push the tag `vX.Y.Z` on that commit.
2. CI builds and verifies the publication as on `main`. The `release` job, in the protected
   `maven-central` environment, signs it with `.github/central-bundle.sh` and uploads it to the Central
   Portal as a user-managed deployment. It runs no Gradle, and the build job never sees the secrets.
3. Compare the signed files, the run's `maven-release` artifact (`gh run download <run> -n maven-release`),
   with a local build of the tag using the pinned toolchains. Then publish the deployment in the Portal
   (irreversible) or drop it, and commit the next `-SNAPSHOT` version.

Releases are signed by `Klethron release signing <release@klethron.com>`, fingerprint
`361C CF78 F83A DAED D8DC  6DA8 A32D AC0A CDC6 D35B`, in [`release-signing-key.asc`](release-signing-key.asc)
and on `keys.openpgp.org` and `keyserver.ubuntu.com`. Its ed25519 signing subkey `2F78 F885 F811 292A 6199  2A11 8C31 C50B B472 B529`
expires 2028-11-08; the primary key stays offline. Import the file, then check a download with
`gpg --verify tsnet-X.Y.Z.aar.asc tsnet-X.Y.Z.aar`.

The environment needs a required reviewer, deployments from `v*` tags only, and the secrets `SIGNING_KEY`
(only the signing subkey, `gpg --export-secret-subkeys`), `SIGNING_PASSPHRASE`, `CENTRAL_USERNAME` and
`CENTRAL_PASSWORD` (a Central Portal user token). `central-bundle.sh` fails unless every signature
verifies with `release-signing-key.asc`. After renewing or replacing the subkey, commit and upload the
new public key.
