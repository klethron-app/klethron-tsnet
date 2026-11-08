package com.klethron.tsnet;

import android.Manifest;
import android.os.ParcelFileDescriptor;
import androidx.annotation.AnyThread;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/// Owns a native tsnet node. Construction does not create credentials, access storage or join a
/// tailnet; [#start] does, and [#dial] then connects to its peers. Close explicitly: there is no
/// finalizer.
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
        String record = nativeAwaitStatus(handle, previous == null ? -1 : previous.version, millis(timeout));
        if (record == null) {
            throw new IllegalStateException("Node is closed");
        }
        return TsnetStatus.parse(record);
    }

    /// Opens a TCP connection to `host` through the tailnet, waiting up to `timeout` for this node
    /// to be [TsnetStatus.State#RUNNING] and the connection to be established.
    ///
    /// Only peers and subnet routes in this node's network map are reachable: the connection never
    /// uses the device's own network, and other addresses fail with [NoRouteToHostException].
    ///
    /// The result is a connected Unix stream socket relaying the connection. Read and write it as a
    /// blocking stream, or make it non-blocking and poll it with [android.system.Os]. End of stream
    /// means the peer closed its side; the connection stays writable. Closing the descriptor or
    /// shutting down its output closes the connection: half-closing towards the peer is not
    /// supported. Closing the node closes all of its connections.
    ///
    /// @param host a tailnet IP address literal or MagicDNS name, at most 255 UTF-8 bytes
    /// @return the connection, which the caller must close
    /// @throws IllegalArgumentException if the host is empty or too long, or the port is not 1–65535
    /// @throws IllegalStateException if this node is not started, failed to start, or is or becomes
    ///     closed
    /// @throws UnknownHostException if the tailnet's DNS cannot resolve `host`
    /// @throws NoRouteToHostException if the address is not reachable through the tailnet
    /// @throws SocketTimeoutException if the timeout elapses first
    /// @throws ConnectException if the connection fails, for example because it was refused
    @WorkerThread
    public ParcelFileDescriptor dial(String host, int port, Duration timeout) throws IOException {
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > 255 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Host must have 1–255 bytes and port must be 1–65535");
        }
        int result = nativeDial(handle, name, port, millis(timeout));
        check(result);
        return ParcelFileDescriptor.adoptFd(result);
    }

    /// Logs this node out of its tailnet. Control expires the node's key, ending its tailnet
    /// access, and the login is deleted from the state directory. The node remains started and
    /// soon reports [TsnetStatus.State#NEEDS_LOGIN] without a login URL; its connections stop
    /// working. Has no effect if the node is not logged in.
    ///
    /// To log in again, close this node and start a new one with the same directory. To also
    /// forget the device key that remains there, close this node and delete the directory.
    ///
    /// If the logout fails, the node is [TsnetStatus.State#STOPPED] but remains registered, and its
    /// login persists until a logout succeeds: retry, possibly from a new node started with the same
    /// directory, which resumes the login. Deleting the directory instead leaves the device in the
    /// tailnet until an administrator removes it or its key expires.
    ///
    /// @throws IllegalStateException if this node is not started, failed to start, or is or becomes
    ///     closed
    /// @throws SocketTimeoutException if control does not confirm the logout within `timeout`
    /// @throws IOException if the logout fails
    @WorkerThread
    public void logout(Duration timeout) throws IOException {
        check(nativeLogout(handle, millis(timeout)));
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
    /// Waiting [#awaitStatus] and [#dial] calls throw, and dialed connections close. Persistent
    /// state remains. Repeated or concurrent calls are safe and have no additional effect.
    @WorkerThread
    @Override
    public void close() {
        nativeClose(handle);
    }

    /// Throws the exception for a negative native result.
    private static void check(int result) throws IOException {
        switch (result) {
            case -1 -> throw new IllegalStateException("Node is closed");
            case -2 -> throw new IllegalStateException("Node is not started");
            case -3 -> throw new IllegalArgumentException("Invalid host");
            case -4 -> throw new UnknownHostException("Tailnet DNS cannot resolve the host");
            case -5 -> throw new NoRouteToHostException("Address is not reachable through the tailnet");
            case -6 -> throw new SocketTimeoutException("Timed out");
            case -7 -> throw new ConnectException("Tailnet connection failed");
            case -8 -> throw new IOException("Logout failed");
            default -> { }
        }
    }

    /// Clamps to 0–`Long.MAX_VALUE` milliseconds; [Duration#toMillis] would throw on overflow.
    private static long millis(Duration timeout) {
        if (timeout.isNegative()) return 0;
        return timeout.compareTo(Duration.ofMillis(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : timeout.toMillis();
    }

    private static native long nativeCreate();

    private static native int nativeStart(long handle, byte[] config);

    private static native @Nullable String nativeAwaitStatus(long handle, long afterVersion, long timeoutMillis);

    private static native int nativeDial(long handle, byte[] host, int port, long timeoutMillis);

    private static native int nativeLogout(long handle, long timeoutMillis);

    private static native void nativeClose(long handle);

    private static native String nativeTailscaleVersion();

    private static native void nativeSetDefaultNetwork(byte[] name, int gateway);
}
