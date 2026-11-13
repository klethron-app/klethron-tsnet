[![Build](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.klethron/tsnet)](https://central.sonatype.com/artifact/com.klethron/tsnet)
[![Javadoc](https://javadoc.io/badge2/com.klethron/tsnet/javadoc.svg)](https://javadoc.io/doc/com.klethron/tsnet/latest/)

# Klethron tsnet

Connect Android apps to [Tailscale](https://tailscale.com/) over TCP. The embedded
[tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet) node has its own identity;
no Tailscale app or device-wide VPN is needed.

Requires **Android 14 (API 34)** or later. The AAR supports **arm64-v8a**, **x86_64** and 16 KiB pages;
consumers need no Go compiler or NDK.

**Under development.** Login, saved identity, logout and revocation have local and live tailnet tests.
The `SocketChannel` API, IPv6 connections and peer listing have local coverage. The underlying TCP
transport and Wi-Fi/cellular switches also have live coverage, the latter only with Tailscale's VPN
active. See [limitations](UPSTREAM.md#limits) and [Javadoc](https://javadoc.io/doc/com.klethron/tsnet/latest/).

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

The manifest adds `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE`, all granted without a prompt.

## Connect

Run `TailnetNode` operations on a worker thread. Give each node a directory under
`Context.getNoBackupFilesDir()`; it holds private keys and must never be logged, backed up or shared.

```java
File directory = new File(context.getNoBackupFilesDir(), "tailscale");
try (TailnetNode node = TailnetNode.start(context, directory, "my-device")) {
    NodeStatus status = null;
    do {
        status = node.awaitStatus(status, Duration.ofSeconds(30));
        // Offer loginUrl() in the user's browser; never log it. Show error() if present.
    } while (status.state() != NodeStatus.State.RUNNING || status.loginUrl() != null);

    try (SocketChannel connection = node.connect("my-server", 443, Duration.ofSeconds(30))) {
        // Use connection.socket() for sequential request/response streams and read timeouts,
        // or connection.configureBlocking(false) with a Selector.
    }
}
```

`awaitStatus` waits without polling; closing from another thread cancels it with `ClosedChannelException`.
Whoever opens a login URL can claim the node. Wait for it to clear before closing so the login is saved.
Restarting with the same directory resumes that identity.

`NodeStatus` reports the device name, tailnet, user and peers (names, OS, online state, addresses and
[Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) host keys).

`connect` returns a caller-owned, connected blocking `SocketChannel` to a tailnet IP or MagicDNS name.
It handles the local socket bridge internally; socket addresses and options describe that bridge.
For concurrent reads and writes, use the channel directly; Android's channel-backed streams share a lock.
Only peers and subnet routes are reachable; names resolve only through the tailnet.
`close` ends connections and keeps the identity. `logout`, key expiry and device removal end access
and connections; `login` requests authorization again.
The app controls node lifetime and services.

## Network

Nodes follow the best non-VPN network across Wi-Fi and mobile changes, including while another VPN
is active. TLS uses Android's system CA store minus user-disabled roots; user-added CAs and the app's
network security configuration do not apply.

See [CONTRIBUTING.md](CONTRIBUTING.md) to build, test or use a checkout. Original code is
[Apache-2.0 licensed](LICENSE); dependencies' [own licences](THIRD-PARTY-NOTICES.md) ship in the AAR.
