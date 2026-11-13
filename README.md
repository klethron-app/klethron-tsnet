[![Build](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.klethron/tsnet)](https://central.sonatype.com/artifact/com.klethron/tsnet)
[![Javadoc](https://javadoc.io/badge2/com.klethron/tsnet/javadoc.svg)](https://javadoc.io/doc/com.klethron/tsnet/latest/)

# Klethron tsnet

Connect an Android app to a [Tailscale](https://tailscale.com/) network over TCP. Each embedded
[tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet) node runs in your process with its own
identity, without the Tailscale app or a device-wide VPN.

Requires **Android 14 (API 34)** or later. The AAR includes native libraries for **arm64-v8a** and
**x86_64**, with 16 KiB page support. Consumers need no Go compiler or NDK.

**Under development.** Login, saved identity, TCP dialing, logout and revocation have local and live
tailnet tests. Wi-Fi/cellular switches have live coverage only with Tailscale's VPN active; peer
listing has local coverage. See [limitations](UPSTREAM.md#limits) and [Javadoc](https://javadoc.io/doc/com.klethron/tsnet/latest/).

## Install

Add `mavenCentral()` and select a [release version](https://central.sonatype.com/artifact/com.klethron/tsnet):

```kotlin
dependencies {
    implementation("com.klethron:tsnet:<version>")
}
android {
    // The bundled libraries are already stripped.
    packaging.jniLibs.keepDebugSymbols += "**/libklethron_tsnet.so"
}
```

The manifest adds `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE` (for Wi-Fi/cellular
switches). All are granted without a prompt.

## Connect

Run `TailnetNode` operations on a worker thread. Give each node a directory under
`Context.getNoBackupFilesDir()`; it holds private keys and must never be logged, backed up or shared.

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

`awaitStatus` waits without polling; closing cancels it with `ClosedChannelException`. Whoever opens
a login URL can claim the node. Wait for the URL to clear before closing so the login is saved.
Starting with the same directory resumes that identity.

`NodeStatus` reports the device name, tailnet and user. Its `peers()` list includes MagicDNS names,
OS, online state, addresses and [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) host keys
for device selection and SSH verification without trust-on-first-use.

`dial` returns a caller-owned socket to a tailnet IP or MagicDNS name. For NIO selectors, pass one end
of a loopback TCP pair to its socket overload. Only peers and subnet routes are reachable; names
resolve only through the tailnet. `close` ends connections and keeps the identity. `logout`, key
expiry and device removal end access and connections; `login` requests authorization again.
The app controls node lifetime and services.

## Network

Nodes follow the best non-VPN network across Wi-Fi and mobile changes, including while another VPN
is active. TLS uses Android's system CA store minus user-disabled roots; user-added CAs and the app's
network security configuration do not apply.

See [CONTRIBUTING.md](CONTRIBUTING.md) to build, test or use a checkout. Original code is
[Apache-2.0 licensed](LICENSE); dependencies' [own licences](THIRD-PARTY-NOTICES.md) ship in the AAR.
