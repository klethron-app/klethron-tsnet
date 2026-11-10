[![Build](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.klethron/tsnet)](https://central.sonatype.com/artifact/com.klethron/tsnet)
[![Javadoc](https://javadoc.io/badge2/com.klethron/tsnet/javadoc.svg)](https://javadoc.io/doc/com.klethron/tsnet/latest/)

# Klethron tsnet

Connect an Android app to a [Tailscale](https://tailscale.com/) network and open TCP connections to its
peers. The library embeds [tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet) in your app's
process. Each node has its own identity; it needs neither the Tailscale app nor a device-wide VPN.

Requires **Android 14 (API 34)** or later. The AAR includes native libraries for **arm64-v8a** and
**x86_64**, with 16 KiB page support. Consumers need no Go compiler or NDK.

**Under development.** Login, persistent identity, TCP dialing, logout, revocation and Wi-Fi/cellular
switches have been tested locally and on a real tailnet, including with the Tailscale app's VPN active.
Peer listing has been tested locally. Broader network and device coverage is still needed; see
[known limitations](UPSTREAM.md#limits). See the
[Javadoc](https://javadoc.io/doc/com.klethron/tsnet/latest/) for the full API.

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

The library's manifest adds the `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE`
permissions to your app. All three are granted at install without a prompt.

## Connect

A [`TailnetNode`][TailnetNode] represents your app as a device on a tailnet (a private Tailscale network).
Run node operations on a worker thread. Keep its state in a dedicated directory under
[`Context.getNoBackupFilesDir()`](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()):
it contains private keys and must never be logged, backed up or shared.

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
            // Show the error to the user, who can cancel: closing the node from another thread
            // makes the waiting call throw ClosedChannelException.
        }
    } while (status.state() != NodeStatus.State.RUNNING || status.loginUrl() != null);

    try (ParcelFileDescriptor connection = node.dial("my-server", 443, Duration.ofSeconds(30))) {
        // Read/write the connected socket using android.system.Os or file streams.
    }
}
```

[`awaitStatus`][awaitStatus] waits for changes without polling. A login URL lets whoever opens it claim
the node; pass it only to the user's browser. Wait for the URL to clear before closing, so the login is saved.
Starting with the same directory then resumes that identity.

[`NodeStatus.peers`][peers] lists the other devices this node can see, with their MagicDNS name, operating
system, online state, addresses and [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) host
keys, so users can pick a device instead of typing its name, and SSH clients can verify it without
trust-on-first-use. Each change,
including a device going online or offline, is a status change.

[`dial`][dial] accepts a tailnet IP address or MagicDNS name and returns a socket descriptor owned by the
caller. It reaches peers and subnet routes only. [`close`][close] ends its connections but preserves
its identity. [`logout`][logout] ends tailnet access; [`login`][login] starts authorization again after
logout or key expiry. The app decides how long to keep the node alive and whether to run a service.

## Network

Running nodes follow the device's best non-VPN network, so they reconnect after switching Wi-Fi or
mobile networks, also while another VPN app, such as Tailscale's, is active. Without
`CHANGE_NETWORK_STATE`, Android would not let them follow Wi-Fi to cellular; if you remove that
permission, they follow the default network, which stays the VPN while one is active. Native TLS uses Android's system CA store, excluding
disabled roots; user-added CAs and the app's network security configuration do not apply.

See [CONTRIBUTING.md](CONTRIBUTING.md) to build, test or use a checkout. Original code is
[Apache-2.0 licensed](LICENSE); bundled dependencies retain their
[own licences](THIRD-PARTY-NOTICES.md), included in the AAR.

[TailnetNode]: https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/TailnetNode.html
[awaitStatus]: https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/TailnetNode.html#awaitStatus(com.klethron.tsnet.NodeStatus,java.time.Duration)
[peers]: https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/NodeStatus.html#peers()
[dial]: https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/TailnetNode.html#dial(java.lang.String,int,java.time.Duration)
[close]: https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/TailnetNode.html#close()
[logout]: https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/TailnetNode.html#logout(java.time.Duration)
[login]: https://javadoc.io/doc/com.klethron/tsnet/latest/com/klethron/tsnet/TailnetNode.html#login()
