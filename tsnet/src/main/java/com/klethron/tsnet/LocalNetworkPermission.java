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
    private record Offer(NodeStatus.Peer peer, Runnable callback) {}

    private static volatile Network network = new Network(List.of());
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile @Nullable Offer offer;
    private volatile boolean closed;

    LocalNetworkPermission(Context context) {
        this.context = context;
    }

    static void report(List<IpPrefix> prefixes) {
        network = new Network(prefixes.stream().filter(prefix -> prefix.getPrefixLength() > 0).toList());
    }

    synchronized void set(NodeStatus.@Nullable Peer peer, @Nullable Runnable callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Register permission offers on the main thread");
        }
        if ((peer == null) != (callback == null)) {
            throw new IllegalArgumentException("Peer and callback must both be present or both be null");
        }
        if (closed) throw new IllegalStateException("Node is closed");
        offer = peer == null ? null : new Offer(peer, java.util.Objects.requireNonNull(callback));
    }

    @SuppressWarnings("ReferenceEquality") // Identity rejects even equal replacement registrations/networks.
    void denied(byte[] peer, byte[] destination) {
        Offer pending = offer;
        Network attempted = network;
        if (closed || pending == null) return;
        try {
            InetAddress address = InetAddress.getByAddress(destination);
            if (!pending.peer.addresses().contains(InetAddress.getByAddress(peer))
                    || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
                    || attempted.prefixes.stream().noneMatch(prefix -> prefix.contains(address))) return;
        } catch (java.net.UnknownHostException invalid) {
            return;
        }
        main.post(() -> {
            if (closed || offer != pending || network != attempted || !missing()) return;
            offer = null; // One offer, even when several packets failed before main-thread delivery.
            pending.callback.run();
        });
    }

    private boolean missing() {
        return Build.VERSION.SDK_INT >= 37 && context.getApplicationInfo().targetSdkVersion >= 37
                && context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED;
    }

    synchronized void close() {
        closed = true;
        offer = null;
    }
}
