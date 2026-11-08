package com.klethron.tsnet;

import android.Manifest;
import androidx.annotation.AnyThread;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/// Owns a native tsnet node. Construction does not create credentials, access storage or join a
/// tailnet; [#start] does. Close explicitly: there is no finalizer. Dialing is not implemented yet.
public final class TsnetNode implements AutoCloseable {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private final long handle;

    /// Creates a node. The first creation in a process reads Android's CA store from disk.
    @WorkerThread
    public TsnetNode() {
        handle = nativeCreate();
    }

    /// Starts connecting this node to Tailscale, logging in interactively. Returns after local
    /// initialization, without waiting for the network; follow progress with [#awaitStatus].
    ///
    /// The node's identity, including its private keys, persists in `stateDirectory`, which is
    /// created if missing. Use a directory that only this node uses, under the app's no-backup
    /// storage (`Context.getNoBackupFilesDir()`), and delete it to forget the node. Logs and network
    /// maps are not written there. Starting again with the same directory resumes without logging in.
    ///
    /// @param hostname the requested tailnet machine name; control may adjust it
    /// @throws IllegalArgumentException if the directory is relative or the hostname is empty
    /// @throws IllegalStateException if this node was already started or is closed
    /// @throws IOException if the directory is unusable or the process environment sets
    ///     `TS_AUTHKEY`/`TS_AUTH_KEY`, which upstream would otherwise use silently
    @WorkerThread
    @RequiresPermission(Manifest.permission.INTERNET)
    public void start(File stateDirectory, String hostname) throws IOException {
        start(stateDirectory, hostname, "");
    }

    /// As [#start(File, String)], with an alternative control server URL for tests.
    void start(File stateDirectory, String hostname, String controlUrl) throws IOException {
        if (!stateDirectory.isAbsolute() || hostname.isEmpty()) {
            throw new IllegalArgumentException("State directory must be absolute and hostname non-empty");
        }
        String config = stateDirectory.getPath() + '\0' + hostname + '\0' + controlUrl;
        switch (nativeStart(handle, config.getBytes(StandardCharsets.UTF_8))) {
            case 0 -> { }
            case 1 -> throw new IllegalStateException("Node is closed");
            case 2 -> throw new IllegalStateException("Node was already started");
            case 3 -> throw new IllegalArgumentException("Invalid node configuration");
            case 4 -> throw new IOException("TS_AUTHKEY or TS_AUTH_KEY is set in the process environment");
            default -> throw new IOException("Node failed to start");
        }
    }

    /// Returns this node's status once it differs from `previous`, or when `timeout` elapses.
    ///
    /// With a `null` previous status, returns the current status immediately. Only the latest
    /// status is kept: intermediate states may be skipped. Closing the node wakes waiting threads.
    ///
    /// @throws IllegalStateException if the node is or becomes closed
    @WorkerThread
    public TsnetStatus awaitStatus(@Nullable TsnetStatus previous, Duration timeout) {
        long millis = timeout.isNegative() ? 0 : timeout.toMillis();
        String record = nativeAwaitStatus(handle, previous == null ? -1 : previous.version, millis);
        if (record == null) {
            throw new IllegalStateException("Node is closed");
        }
        return TsnetStatus.parse(record);
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

    /// Stops and releases this node, waiting for a concurrent [#start] and shutdown to finish.
    /// Persistent state remains. Repeated or concurrent calls are safe and have no additional effect.
    @WorkerThread
    @Override
    public void close() {
        nativeClose(handle);
    }

    private static native long nativeCreate();

    private static native int nativeStart(long handle, byte[] config);

    private static native @Nullable String nativeAwaitStatus(long handle, long afterVersion, long timeoutMillis);

    private static native void nativeClose(long handle);

    private static native String nativeTailscaleVersion();

    private static native void nativeSetDefaultNetwork(byte[] name, int gateway);
}
