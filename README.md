# Klethron tsnet

An Android AAR containing a precompiled Tailscale tsnet engine and a small Java/JNI bridge.
Java package: `com.klethron.tsnet`. Maven coordinates: `com.klethron:tsnet:0.1.0-SNAPSHOT`.

**Early development:** a public API starts a node with persistent state in a caller-owned directory,
reports its login URL and backend status, opens TCP connections to tailnet peers, logs out or in again,
and closes it. It is verified against an in-process loopback test control server and peer, including
interactive login, key expiry, device approval, resuming from saved state and dialing by address or
MagicDNS name. A user-run manual test of the R8-minified demo on arm64 hardware also passed real-tailnet
browser login, TCP echo by IPv4 address and MagicDNS name, saved-login restart, and logout/re-login.
An ADB-driven follow-up also verified fresh TCP echoes after Wi-Fi → cellular → Wi-Fi switches on
the same running node, echoes relayed only through Tailscale's DERP servers, and admin-initiated key
expiry and device removal. Debug-only tests also start nodes against
a rejecting loopback control endpoint, connect two in-process nodes through a loopback test control
and DERP server to exchange TCP data over direct and relay-only paths, and rebind when the device's
default network changes. Another contacts Tailscale's real control plane without credentials,
stopping at an unauthorized login URL that never leaves native code. No package registry release
exists yet.
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

The package is also Error Prone `@CheckReturnValue`: an ignored `new TsnetNode()` can never be closed.
Construction is `@WorkerThread`, because the first node in a process reads Android's CA store from disk;
so are `start`, `awaitStatus`, `dial`, `logout`, `login` and `close`. `tailscaleVersion` and `setDefaultNetwork` are `@AnyThread`. Android lint (`ThreadConstraint`, `CheckResult`), Error Prone and
Kotlin's opt-in `-Xreturn-value-checker` report misuse from Java and Kotlin. They read these annotations
by name, so the AAR does not export `androidx.annotation` (which would add the Kotlin stdlib) or
`error_prone_annotations`. `start` is `@RequiresPermission(INTERNET)`. Callers must close nodes
explicitly; `close()` is idempotent, safe to call concurrently, and waits for shutdown.

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
    // Connected. Closing the node disconnects it; its state remains for the next start.
    try (ParcelFileDescriptor connection = node.dial("my-server", 22, Duration.ofSeconds(30))) {
        // A connected stream socket: use FileInputStream/FileOutputStream, or android.system.Os.
    }
}
```

`dial` accepts a tailnet address or MagicDNS name and waits for the node to be running. It reaches only
peers and subnet routes in the node's network map, through the in-process network stack: unlike
upstream `tsnet.Server.Dial`, it never falls back to the device's own network for other addresses
(`NoRouteToHostException`). The result is one end of a Unix socket pair that the library relays to the
tailnet connection, so callers can block on it, or make it non-blocking and poll it with
`android.system.Os`. End of stream means the peer closed its side; the connection remains writable.
Closing the descriptor, or shutting down its output, closes the connection. Closing the node closes all
of its connections.

`logout` asks control to expire the node's key and deletes the login from the state directory; the node
stays in `NEEDS_LOGIN` without a login URL. To also forget the device key, close the node and delete the
directory. A failed logout leaves the node `STOPPED` with its login intact, so it can be retried.

`login` starts a new interactive login on a started node. Call it when the node reports `NEEDS_LOGIN`
without a login URL: after a logout, or when control revoked the login. The status then says why:
`revocation()` is `KEY_EXPIRED` once the node key expired (by default, Tailscale node
keys expire periodically, and administrators can expire them), or `NODE_REMOVED` once the device was
removed from the tailnet; logging in again then adds a new device. It is `null` after a logout.
Starting a node logs in by itself when needed. On a logged-in node, `login`
re-authenticates it, renewing its key while it keeps running. A login URL stays in the status until the
new login is saved; close the node only after that, or the next start asks to log in again.
`error()` holds the latest backend error, such as control rejecting a registration, until the node next
runs. Its text comes from upstream or control: show it to the user, but do not parse or log it.

`start` returns once the node is initialized locally; `awaitStatus` returns the latest status after it
changes, waking when the node is closed. Status records keep only the latest state, so intermediate
states can be skipped. Every connection-relevant setting is explicit: the control URL is Tailscale's,
not `TS_CONTROL_URL`, and `start` refuses to run if the process environment (as seen when the library
loaded) sets `TS_AUTHKEY` or `TS_AUTH_KEY`, which upstream would otherwise use silently. State lives in
`tailscaled.state` (mode 0600) in the given directory, which is created with mode 0700 if missing.

Both build variants omit 18 upstream features with build tags (see `tsnet/build.gradle.kts`), including
Tailscale SSH, the embedded web client, logtail, debug hooks, PeerAPI serving, exit nodes, environment
proxies, OAuth client secrets, MDM policy and the on-disk netmap cache. This keeps the host app's
environment from redirecting connections or enabling TLS key logging, and reduces native size.
The netmap cache would otherwise persist peer names, addresses and
keys in the node directory whenever the control server enables it. Two small patches also skip upstream log-configuration creation and socket-log directory lookup
when logtail is disabled. Both user and backend loggers discard output. A third keeps the node key after
a failed logout, so the login survives and the logout can be retried. Patches are applied to an
isolated, verified build copy; the shared module cache is untouched. The AAR ships the patch notice.
The patch implementation is part of Gradle's task fingerprint, so edits invalidate native builds;
Go's cache also tracks the modified source. Shared native output caching is disabled.
See [PLAN.md](PLAN.md) for verification and remaining startup work.

Native TLS verification uses Android's current system CA store: the updatable Conscrypt APEX directory
on Android 14+, without roots the user disabled in Settings. Stock Go would read only the system image
copy, missing root changes delivered by Google Play system updates. User-added CAs and the consuming
app's network security configuration do not apply to native TLS. Tailscale's built-in Let's Encrypt
fallback roots are omitted, so a root disabled on the device is not trusted for control or DERP either.

Android does not let native code discover the default network, so the app reports it with
`TsnetNode.setDefaultNetwork(interfaceName, ipv4Gateway)`, for example from a
`ConnectivityManager.registerDefaultNetworkCallback` callback's `onLinkPropertiesChanged` and `onLost`.
Without it, started nodes cannot recognize network changes, rebind sockets or reconnect DERP. The values
are process-wide; registering and unregistering the callback is the caller's responsibility.
The library discards Go's standard logger, which upstream uses directly for some messages.

The engine runs inside the Android app process using Go goroutines and native threads. The library
does not launch executables or subprocesses. Call blocking methods off the UI thread.

The demo retains an offline button that loads the library and creates/closes a node. Its separate,
opt-in live screen uses INTERNET and ACCESS_NETWORK_STATE permissions to start a persistent node,
authorize in a browser and verify a TCP echo from a tailnet peer; see the manual test below.
JNI instrumentation tests interface discovery,
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
them. `LoginTest` starts nodes through the public API against a loopback test control server that requires
interactive login, completes the login in place of a browser, and restarts from the saved state. It also
expires node keys, answers map requests as for removed devices, requires device approval and rejects
registrations at that control server, checking the reported revocations and errors.
`DialTest` dials an in-memory peer on a loopback test tailnet through the public API: a 1 MiB echo by
address and MagicDNS name, peer end of stream, closing, refused, unroutable and timed-out dials.
The automated tests do not prove authorized connectivity to a real tailnet. `ControlPlaneTest` needs internet access: it resolves and verifies
`controlplane.tailscale.com` through the platform, then starts an ephemeral in-memory node until control
returns a `login.tailscale.com` URL. The URL is validated in Go and never returned, logged or opened.

The release demo enables R8 and resource shrinking and uses the local debug signing key solely for
installation/testing. `build` assembles it and its instrumentation APK; `:demo:connectedReleaseAndroidTest`
exercises native load, version reporting and repeated create/close, plus live-screen startup/restart
until browser authorization is available (or a saved login connects). The live-screen test needs
internet; it never opens the browser or changes an existing login. Neither test references `TsnetNode`
or adds JNI keep rules. The demo uses the standard optimized Android rules and the AAR's consumer rules.

## Live tailnet test

Hardware smoke coverage now includes a Pixel 11 Pro XL, API 37, arm64-v8a, 4 KiB pages and SELinux
Enforcing: all 25 library tests passed over USB on 2026-11-09, including network switching; the 2
release-demo tests passed on 2026-11-08. On 2026-11-08, the user also reported all
steps below passing with the release demo and a separate computer as the echo peer: browser login,
IPv4/MagicDNS echo, saved-login restart, and logout/re-login. This manual result does not identify
direct versus DERP paths. An ADB-driven follow-up on 2026-11-09 verified fresh 52-byte echoes on Wi-Fi
(50 ms), cellular (1,126 ms), and restored Wi-Fi (37 ms), without restarting the node. Those timings
measure dial plus exchange, not network handoff. A second follow-up with **Keep connection open**
verified that one TCP stream kept echoing across Wi-Fi → cellular → Wi-Fi, with the peer seeing the same
connection throughout (see PLAN.md). Wi-Fi was restored afterward. A third follow-up used the peer's
path status to confirm direct paths on Wi-Fi and through the carrier's NAT on LTE. It also showed that
logout ends an open stream and fresh dials, and that browser re-login restores the echo. A fourth
broke system DNS with an unresolvable strict Private DNS hostname: a fresh process still started with
the saved login and echoed by address and MagicDNS name. A fifth used a peer that could not use UDP:
fresh and kept-open echoes, on Wi-Fi and cellular, all went through Tailscale's DERP relay. A sixth
expired the node's key through the Tailscale API and later removed the node in the admin console: each
time the demo showed NEEDS_LOGIN, the open stream and fresh dials failed, and browser re-login restored
the echo (after removal as a new node). To repeat the basic test, use a tailnet and peer you own.

1. Install the minified consumer with `./gradlew :demo:installRelease` (set `ANDROID_SERIAL` when more
   than one device is attached). Open **Klethron tsnet demo → Live tailnet test → Start / resume node**.
2. Tap **Authorize in browser**, log into the intended tailnet and approve the node. Return to the
   demo and wait for **RUNNING / Login saved**. Do not close while authorization is pending: the new
   login may not yet be persisted. No auth key is needed. Never copy the URL or node state into reports.
3. On a separate tailnet peer, run the following echo server, replacing `100.x.y.z` with that peer's
   Tailscale IPv4 address. Allow TCP 7070 from the test node in the tailnet policy and peer firewall.
   The server binds only that address; it does not print payloads.

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

4. Enter the peer address and port `7070`, then tap **Verify TCP echo**. Expect **Echo verified: 52 B**
   and elapsed time. Repeat with the peer's full MagicDNS name. Each exchange sends a fresh random
   challenge and checks every returned byte. Dial and exchange each time out after 10 seconds.
   Tick **Keep connection open between echoes** to reuse one stream instead (for network-change
   tests); untick it to close the stream. The server above closes connections idle for 15 seconds.
5. Tap **Close node**, reopen the live screen and Start again. Expect RUNNING without browser login;
   repeat the echo. Then **Log out of tailnet**, wait for confirmation and tap **Verify TCP echo**
   again (with and without a kept-open stream): it must fail. **Log in / renew key**, authorize again
   and repeat. A failed logout retains the login; restore connectivity and retry it.

The node stays alive while its screen is behind the browser. Destroying the screen (including rotation)
queues cleanup off the UI thread; Start resumes the saved state when reopened. The app has no service
and cannot promise background lifetime. Identity is excluded from backup; uninstalling/clearing app
data discards it locally but does not remove the machine from the tailnet admin console.

Record only test outcomes, device/API/ABI/page size, network types and timings:

| Case | Evidence required |
|---|---|
| Login, address/MagicDNS echo, saved-login restart | RUNNING, Login saved, exact echo before and after restart |
| Logout/re-login, admin key expiry/removal | Access ends; observe state; browser re-authentication restores echo. Key expiry needs the API (`POST /api/v2/device/{id}/expire` with a short-lived token the user runs and revokes); removal is in the admin console |
| Wi-Fi ↔ cellular, different NATs | Fresh and kept-open echoes succeed after each switch; the peer sees one unchanged connection for the latter (`ss -tn`); use USB ADB |
| Direct and real DERP | Peer-side path evidence during exchange (`tailscale status` on the peer shows `direct` or `relay`); successful echo alone does not identify the path. For DERP, block the peer's UDP in a controlled test network, or run an ephemeral tsnet echo peer with `TS_DEBUG_ALWAYS_USE_DERP=true` (no root; it opens no UDP sockets) and log it out afterwards |
| Fallback DNS | System DNS deliberately fails while control/DERP remain reachable (for example `adb shell settings put global private_dns_mode hostname` and `private_dns_specifier` set to an unresolvable name; delete both afterwards); a fresh process starts and a fresh connection succeeds |

Do not claim forced-DERP or fallback-DNS coverage without those conditions. The demo does not expose
path diagnostics. Stop the echo server and log out/remove the test node when finished.

For hardware automation over wireless ADB, exclude the test that disables Wi-Fi:

```sh
ANDROID_SERIAL=<serial> ./gradlew :tsnet:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.klethron.tsnet.NetworkChangeTest
ANDROID_SERIAL=<serial> ./gradlew :demo:connectedReleaseAndroidTest
```

With USB ADB and working Wi-Fi plus cellular, run the full library suite instead. Count results from
JUnit XML under each module's `build/outputs/androidTest-results/connected/`; record explicit
exclusions separately from JUnit skips.

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
their own licences. Both AAR variants include an inventory and notices for the 26 Go modules recorded
in their native binaries. `build` checks module versions/checksums and packaged notice contents for
both ABIs. Source-level, embedded-resource and native toolchain attribution review remains a release
prerequisite. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
