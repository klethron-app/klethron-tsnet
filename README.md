[![Build](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/klethron-app/klethron-tsnet/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.klethron/tsnet)](https://central.sonatype.com/artifact/com.klethron/tsnet)
[![Javadoc](https://javadoc.io/badge2/com.klethron/tsnet/javadoc.svg)](https://javadoc.io/doc/com.klethron/tsnet)

# Klethron tsnet

Connect an Android app to a [Tailscale](https://tailscale.com/) network and open TCP connections to its
peers. The library embeds [tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet) in your app's
process. Each node has its own identity; it needs neither the Tailscale app nor a device-wide VPN.

Requires **Android 14 (API 34)** or later. The AAR includes native libraries for **arm64-v8a** and
**x86_64**, with 16 KiB page support. Consumers need no Go compiler or NDK.

**Under development.** Login, persistent identity, TCP dialing, logout and revocation have been tested
locally and on a real tailnet. Broader network and device coverage is still needed; see
[known limitations](UPSTREAM.md). Examples below follow the development API; consult the
[versioned Javadoc](https://javadoc.io/doc/com.klethron/tsnet) for released APIs.

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

Declare these permissions in your app's manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Connect

A `TailnetNode` represents your app as a device on a tailnet (a private Tailscale network). Run node
operations on a worker thread. Keep its state in a dedicated directory under
[`Context.getNoBackupFilesDir()`](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()):
it contains private keys and must never be logged, backed up or shared.

```java
try (TailnetNode node = new TailnetNode()) {
    node.start(new File(context.getNoBackupFilesDir(), "tailscale"), "my-device");
    NodeStatus status = null;
    do {
        status = node.awaitStatus(status, Duration.ofSeconds(30));
        if (status.loginUrl() != null) {
            // Offer to open this URL in the user's browser. Never log it.
        }
        if (status.error() != null) {
            // Show the error to the user and allow cancellation.
        }
    } while (status.state() != NodeStatus.State.RUNNING || status.loginUrl() != null);

    try (ParcelFileDescriptor connection = node.dial("my-server", 443, Duration.ofSeconds(30))) {
        // Read/write the connected socket using android.system.Os or file streams.
    }
}
```

`awaitStatus` waits for changes without polling. A login URL lets whoever opens it claim the node;
pass it only to the user's browser. Wait for the URL to clear before closing, so the login is saved.
Starting with the same directory then resumes that identity.

`dial` accepts a tailnet IP address or MagicDNS name and returns a socket descriptor owned by the
caller. It reaches peers and subnet routes only. Closing the node ends its connections but preserves
its identity. `logout` ends tailnet access; `login` starts authorization again after logout or key
expiry. The app decides how long to keep the node alive and whether to run a service.

## Network changes

Forward Android's default-network changes so the node can reconnect after switching Wi-Fi or mobile
networks. Register this callback while using the library and unregister it when finished:

```java
ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
    @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
        TailnetNode.setDefaultNetwork(properties);
    }
    @Override public void onLost(Network network) {
        TailnetNode.setDefaultNetwork(null);
    }
};
connectivityManager.registerDefaultNetworkCallback(callback);
```

Reports apply to all nodes in the process. Native TLS uses Android's system CA store, excluding
disabled roots; user-added CAs and the app's network security configuration do not apply.

See [CONTRIBUTING.md](CONTRIBUTING.md) to build, test or use a checkout. Original code is
[Apache-2.0 licensed](LICENSE); bundled dependencies retain their
[own licences](THIRD-PARTY-NOTICES.md), included in the AAR.
