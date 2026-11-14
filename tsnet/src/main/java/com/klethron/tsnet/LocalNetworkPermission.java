package com.klethron.tsnet;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.IpPrefix;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Posts native denials without running app code on a transport or storage callback thread.
final class LocalNetworkPermission {
    private record Network(List<IpPrefix> prefixes) {}

    private static volatile Network network = new Network(List.of());
    private final Context context;
    private final @Nullable LocalNetworkPermissionHandler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    LocalNetworkPermission(Context context, @Nullable LocalNetworkPermissionHandler handler) {
        this.context = context;
        this.handler = handler;
    }

    static void report(List<IpPrefix> prefixes) {
        network = new Network(prefixes.stream().filter(prefix -> prefix.getPrefixLength() > 0).toList());
    }

    @SuppressWarnings("ReferenceEquality") // A replacement network may reuse the same prefixes.
    void denied(byte[] peer, byte[] destination) {
        Network attempted = network;
        if (closed || handler == null) return;
        InetAddress peerAddress;
        try {
            peerAddress = InetAddress.getByAddress(peer);
            InetAddress address = InetAddress.getByAddress(destination);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
                    || attempted.prefixes.stream().noneMatch(prefix -> prefix.contains(address))) return;
        } catch (java.net.UnknownHostException invalid) {
            return;
        }
        main.post(() -> {
            if (!closed && network == attempted && missing() && handler.canOffer(peerAddress)) {
                handler.offer(peerAddress);
            }
        });
    }

    private boolean missing() {
        return Build.VERSION.SDK_INT >= 37 && context.getApplicationInfo().targetSdkVersion >= 37
                && context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED;
    }

    void close() {
        closed = true;
    }
}
