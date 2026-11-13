[![Build](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.klethron/tsnet)](https://central.sonatype.com/artifact/com.klethron/tsnet)
[![Javadoc](https://javadoc.io/badge2/com.klethron/tsnet/javadoc.svg)](https://javadoc.io/doc/com.klethron/tsnet/latest/)

# Klethron tsnet

Connect an Android app to peers on a [Tailscale](https://tailscale.com/) network over TCP.
Each embedded [tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet) node has its own identity
and runs in your app's process, without the Tailscale app or a device-wide VPN.

Requires **Android 14 (API 34)** or later. The AAR includes native libraries for **arm64-v8a** and
**x86_64**, with 16 KiB page support. Consumers need no Go compiler or NDK.

**Under development.** Login, saved identity, TCP dialing, logout and revocation are tested locally
and on a real tailnet. Wi-Fi/cellular switches have live coverage only with the Tailscale app's VPN
active; peer listing has local coverage. See [known limitations](UPSTREAM.md#limits) and the
[Javadoc](https://javadoc.io/doc/com.klethron/tsnet/latest/).

## Install

Add `mavenCentral()` to your repositories and select a
[release version](https://central.sonatype.com/artifact/com.klethron/tsnet):

```kotlin
dependencies {
    implementation("com.klethron:tsnet:<version>")
}
android {
    // The bundled libraries are already stripped.
    packaging.jniLibs.keepDebugSymbols += "**/libklethron_tsnet.so"
}
```

The manifest adds `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE`, granted without a
prompt. `CHANGE_NETWORK_STATE` lets nodes follow Wi-Fi to cellular; keep it.

## Connect

A [`TailnetNode`](https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/TailnetNode.html)
is a device on a tailnet. Run its operations on a worker thread. Keep its state in a dedicated
directory under `Context.getNoBackupFilesDir()`; this holds private keys and must never be logged,
backed up or shared.

```java
File directory = new File(context.getNoBackupFilesDir(), "tailscale");
try (TailnetNode node = TailnetNode.start(context, directory, "my-device")) {
    NodeStatus status = null;
    do {
        status = node.awaitStatus(status, Duration.ofSeconds(30));
        if (status.loginUrl() != null) {
            // Offer to open this URL in the user's browser. Never log it.
        }
        if (status.error() != null) {
            // Show the error. Close the node from another thread to cancel the wait.
        }
    } while (status.state() != NodeStatus.State.RUNNING || status.loginUrl() != null);

    try (ParcelFileDescriptor connection = node.dial("my-server", 443, Duration.ofSeconds(30))) {
        // Read/write the connection using android.system.Os or file streams.
    }
}
```

`awaitStatus` waits for changes without polling; closing the node cancels it with
`ClosedChannelException`. Whoever opens a login URL can claim the node. Wait for the URL to clear
before closing so the login is saved. Starting with the same directory resumes that identity.
Status snapshots also include the device name, tailnet and signed-in user.

`NodeStatus.peers()` lists visible devices with their MagicDNS name, OS, online state, addresses
and [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) host keys. Use it for a device picker or
to verify Tailscale SSH servers without trust-on-first-use.

`dial` accepts a tailnet IP address or MagicDNS name and returns a socket owned by the caller.
For NIO selectors, pass one end of a loopback TCP pair to its socket overload. Dialing reaches only
peers and subnet routes, and resolves names only through the tailnet. `close` ends connections but
keeps the identity. `logout`, key expiry and device removal end access and connections; `login` starts
authorization again. The app decides how long to keep the node alive and whether to run a service.

## Network

Running nodes follow the device's best non-VPN network across Wi-Fi and mobile changes, also while
another VPN app, such as Tailscale's, is active. Native TLS uses Android's system CA store without
user-disabled roots; user-added CAs and the app's network security configuration do not apply.

See [CONTRIBUTING.md](CONTRIBUTING.md) to build, test or use a checkout. Original code is
[Apache-2.0 licensed](LICENSE); dependencies' [own licences](THIRD-PARTY-NOTICES.md) ship in the AAR.
