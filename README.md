[![Build](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.klethron/tsnet)](https://central.sonatype.com/artifact/com.klethron/tsnet)
[![Javadoc](https://javadoc.io/badge2/com.klethron/tsnet/javadoc.svg)](https://javadoc.io/doc/com.klethron/tsnet/latest/)

# Klethron tsnet

Connect Android apps to [Tailscale](https://tailscale.com/) over TCP. The embedded
[tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet) node has its own identity;
no Tailscale app or device-wide VPN is needed.

Requires **Android 14 (API 34)** or later. The AAR supports **arm64-v8a**, **x86_64** and 16 KiB pages;
consumers need no Go compiler or NDK.

**Under development.** Local tests cover login, logout, revocation, SQLite restart and crash recovery,
`SocketChannel` operations, IPv6, peer listing, split DNS, DNS-over-HTTPS trust, network replacement
and Tailnet Lock persistence.
Live tests cover login, logout, revocation, TCP transport and Wi-Fi/cellular switches; switches have
only been tested with Tailscale's VPN active. Tailnet Lock status and signing APIs are not exposed.
See [limitations](UPSTREAM.md#limits) and [Javadoc](https://javadoc.io/doc/com.klethron/tsnet/latest/).

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

Run `TailnetNode` operations on a worker thread. Supply a `StateStore` with synchronous `read(String)`
and `write(String, byte[])` callbacks; `null` means absent on read and delete on write. It holds private
keys: exclude it from backups, exports and sync. Give each live node exclusive access to its records,
including across processes. See the demo
[SQLite store](demo/src/main/java/com/klethron/tsnet/demo/store/SqliteStore.java) for the tested implementation.

```java
try (TailnetNode node = TailnetNode.start(context, store, "my-device")) {
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
Whoever opens a login URL can claim the node. Wait for it to clear before closing so the login reaches
the store. Restarting with those values resumes that identity.

Callbacks are serialized. Node operations on a callback thread throw `IllegalStateException`;
callbacks must not wait for node operations on other threads. A returned write must be visible to later
reads. The caller chooses durability: it can handle a disk failure, retain state in memory and return
normally. An exception escaping a callback stops the node and makes node operations throw `IOException`.
Close the node before closing or reusing its store. The library creates no state files or directories;
apps using files should keep them under `Context.getNoBackupFilesDir()`.

The same callbacks persist Tailnet Lock authority history when enabled. History is compacted using
Tailscale's retention policy and removed on logout; callers should treat all store keys as opaque.

`NodeStatus` reports the device name, tailnet, user and peers (names, OS, online state, addresses and
[Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) host keys).

`connect` returns a caller-owned, connected blocking `SocketChannel` to a tailnet IP or MagicDNS name.
It handles the local socket bridge internally; socket addresses and options describe that bridge.
For concurrent reads and writes, use the channel directly; Android's channel-backed streams share a lock.
Only peers and subnet routes are reachable; names resolve only through the tailnet.
`close` ends connections and keeps the identity. `logout`, key expiry and device removal end access
and connections; `login` requests authorization again. A status notification gap also ends existing
connections, since it could hide revocation or a change of login. Completing a new login closes
connections opened under the previous node key. Login and logout calls are serialized.
The app controls node lifetime and services.

## Network

Nodes follow the best non-VPN network across Wi-Fi and mobile changes, including while another VPN
is active. TLS uses Android's system CA store minus user-disabled roots; user-added CAs and the app's
network security configuration do not apply.

See [CONTRIBUTING.md](CONTRIBUTING.md) to build, test or use a checkout. Original code is
[Apache-2.0 licensed](LICENSE); dependencies' [own licences](THIRD-PARTY-NOTICES.md) ship in the AAR.
