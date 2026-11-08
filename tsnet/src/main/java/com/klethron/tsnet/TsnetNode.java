package com.klethron.tsnet;

import androidx.annotation.AnyThread;
import androidx.annotation.WorkerThread;
import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/// Owns an unstarted native tsnet node. Networking and configuration are not implemented yet.
/// Close explicitly; construction does not create credentials, access storage or join a tailnet.
public final class TsnetNode implements AutoCloseable {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private long handle;

    /// Creates a node. The first creation in a process reads Android's CA store from disk.
    @WorkerThread
    public TsnetNode() {
        handle = nativeCreate();
    }

    /// Returns the exact Go module version linked into this binary.
    @AnyThread
    public static String tailscaleVersion() {
        return nativeTailscaleVersion();
    }

    /// Reports the app's default network, which native code cannot discover on Android.
    ///
    /// Pass `LinkProperties.getInterfaceName()` of `ConnectivityManager`'s default network and the
    /// IPv4 gateway of its default route (for NAT port mapping), or `null` for either when unknown
    /// or lost. Report every default network and link change: without an interface name, started
    /// nodes cannot recognize network changes and rebind. Values are process-wide; each call makes
    /// every started node re-examine its network. Registering for changes is the caller's choice.
    ///
    /// Safe to call from any thread, such as a `ConnectivityManager` callback; it does not block.
    ///
    /// @throws IllegalArgumentException if the name has more than 15 UTF-8 bytes (Linux `IFNAMSIZ`)
    @AnyThread
    public static void setDefaultNetwork(@Nullable String interfaceName, @Nullable Inet4Address gateway) {
        byte[] name = interfaceName == null ? new byte[0] : interfaceName.getBytes(StandardCharsets.UTF_8);
        if (name.length > 15) {
            throw new IllegalArgumentException("Interface names have at most 15 bytes");
        }
        nativeSetDefaultNetwork(name, gateway == null ? 0 : ByteBuffer.wrap(gateway.getAddress()).getInt());
    }

    /// Releases this node. Repeated or concurrent calls are safe and have no additional effect.
    @AnyThread
    @Override
    public synchronized void close() {
        if (handle != 0) {
            long ownedHandle = handle;
            handle = 0;
            nativeClose(ownedHandle);
        }
    }

    private static native long nativeCreate();

    private static native void nativeClose(long handle);

    private static native String nativeTailscaleVersion();

    private static native void nativeSetDefaultNetwork(byte[] name, int gateway);
}
