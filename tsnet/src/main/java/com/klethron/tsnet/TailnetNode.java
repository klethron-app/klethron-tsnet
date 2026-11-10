package com.klethron.tsnet;

import android.Manifest;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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

/// An embedded tsnet node: one device in a [tailnet](https://tailscale.com/kb/1136/tailnet), with
/// keys stored in a caller-owned directory. It opens outbound TCP connections through a userspace
/// network stack and does not route the Android device's traffic.
///
/// Call [#start] once, follow [#awaitStatus] until the node is authorized and running, then [#dial].
/// Closing the node ends its connections. Methods are thread-safe; `@WorkerThread` methods block.
/// Report network changes through [#setDefaultNetwork]. The first use of this class in a process
/// loads its native library.
public final class TailnetNode implements AutoCloseable {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private final long handle;

    /// Creates an unstarted node. The first creation in a process reads Android's system CA store.
    @WorkerThread
    public TailnetNode() {
        handle = nativeCreate();
    }

    /// Connects to Tailscale, restoring a saved login or requesting browser authorization.
    /// Returns after local initialization; [#awaitStatus] reports progress and the login URL.
    ///
    /// The directory stores this node's private keys. Give each node its own directory under
    /// [android.content.Context#getNoBackupFilesDir()]; it is created if missing. Reusing it after
    /// [#close] restores the same identity. Never back up or share its contents.
    ///
    /// @param hostname the requested device name; Tailscale may adjust it
    /// @throws IllegalArgumentException if the directory is relative or the hostname is empty or contains NUL
    /// @throws IllegalStateException if [#start] was already called, even unsuccessfully, or the node is closed
    /// @throws IOException if initialization fails, including when the process environment sets
    ///     `TS_AUTHKEY` or `TS_AUTH_KEY`; this library only supports browser login
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
        check(nativeStart(handle, config.getBytes(StandardCharsets.UTF_8)));
    }

    /// Waits for a status change, returning the latest snapshot when it changes or `timeout` elapses.
    /// Pass `null` to read immediately, or a previous snapshot from this node to wait. Intermediate
    /// changes may be skipped. Zero or negative timeouts return immediately.
    ///
    /// @throws IllegalArgumentException if `previous` belongs to another node
    /// @throws IllegalStateException if the node is or becomes closed
    @WorkerThread
    public NodeStatus awaitStatus(@Nullable NodeStatus previous, Duration timeout) {
        if (previous != null && previous.owner != handle) {
            throw new IllegalArgumentException("Status belongs to another node");
        }
        String record = nativeAwaitStatus(handle, previous == null ? -1 : previous.version, millis(timeout));
        if (record == null) {
            throw new IllegalStateException("Node is closed");
        }
        return new NodeStatus(handle, record);
    }

    /// Opens a TCP connection to a tailnet address or
    /// [MagicDNS](https://tailscale.com/kb/1081/magicdns) name. `timeout` covers both waiting for
    /// [NodeStatus.State#RUNNING] and connecting; zero or negative values do not wait.
    /// Only peers and subnet routes in this node's network map are reachable. A name without a tailnet
    /// DNS route can be looked up through the device's resolver, but the connection stays in the tailnet.
    ///
    /// The caller owns the returned Unix stream socket. Use [java.io.FileInputStream] and
    /// [java.io.FileOutputStream] for blocking I/O, or [android.system.Os] for non-blocking I/O.
    /// Unread data slows the sender in either direction. End of stream leaves the connection writable,
    /// but a reset or failure also appears as end of stream. Closing the descriptor or shutting down
    /// its output ends the whole connection once written data is delivered. Closing the node ends all
    /// connections at once.
    ///
    /// @param host a tailnet IP address or MagicDNS name, at most 255 UTF-8 bytes
    /// @throws IllegalArgumentException if the host is invalid or the port is not 1–65535
    /// @throws IllegalStateException if this node has not started successfully or is or becomes closed
    /// @throws UnknownHostException if the tailnet's DNS cannot resolve `host`
    /// @throws NoRouteToHostException if the address is outside the tailnet; it never falls back to a local connection
    /// @throws SocketTimeoutException if the timeout elapses first
    /// @throws ConnectException if the connection fails
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

    /// Ends this node's tailnet access and deletes its saved login. The node stays open, reports
    /// [NodeStatus.State#NEEDS_LOGIN] and can [#login] again. Does nothing if already logged out.
    ///
    /// If logout fails, the node stops connecting but keeps its saved login: retry the logout.
    /// Deleting its files instead leaves the device registered in the tailnet. After a successful
    /// logout, [#close] and delete the state directory to also forget the remaining device key.
    ///
    /// @throws IllegalStateException if this node has not started successfully or is or becomes closed
    /// @throws SocketTimeoutException if Tailscale does not confirm within `timeout`
    /// @throws IOException if the logout fails
    @WorkerThread
    public void logout(Duration timeout) throws IOException {
        check(nativeLogout(handle, millis(timeout)));
    }

    /// Requests browser authorization after logout or key expiry, or to renew an existing login.
    /// Returns before authorization; [#awaitStatus] supplies a [NodeStatus#loginUrl()]. A logged-in
    /// node keeps working while the user authorizes it. Initial [#start] requests login automatically.
    ///
    /// @throws IllegalStateException if this node has not started successfully or is closed
    /// @throws IOException if the login cannot be started
    @WorkerThread
    public void login() throws IOException {
        check(nativeLogin(handle));
    }

    /// Returns the bundled Tailscale version, including its source revision.
    @AnyThread
    public static String tailscaleVersion() {
        return nativeTailscaleVersion();
    }

    /// The network to report through [#setDefaultNetwork]: the best one with internet access that is
    /// not a VPN. Request it with [android.net.ConnectivityManager#requestNetwork], which needs
    /// `CHANGE_NETWORK_STATE`. While another app's VPN is active, the app's default network stays that
    /// VPN when Wi-Fi or cellular changes. A best-matching callback can miss cellular after Wi-Fi loss.
    public static final NetworkRequest PHYSICAL_NETWORK = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();

    /// Reports the network that carries tailnet traffic to all nodes in this process, so they can
    /// reconnect after changes. Forward
    /// [android.net.ConnectivityManager.NetworkCallback#onLinkPropertiesChanged] from a callback
    /// registered for [#PHYSICAL_NETWORK], and pass `null` on
    /// [android.net.ConnectivityManager.NetworkCallback#onLost]. Keep the callback registered while
    /// nodes are in use.
    ///
    /// @throws IllegalArgumentException if the interface name exceeds Linux's 15-byte limit
    @AnyThread
    public static void setDefaultNetwork(@Nullable LinkProperties properties) {
        String name = properties == null ? null : properties.getInterfaceName();
        byte[] encoded = name == null ? new byte[0] : name.getBytes(StandardCharsets.UTF_8);
        int gateway = properties == null ? 0 : properties.getRoutes().stream()
                .filter(route -> route.isDefaultRoute() && route.hasGateway() && route.getGateway() instanceof Inet4Address)
                .mapToInt(route -> ByteBuffer.wrap(route.getGateway().getAddress()).getInt()).findFirst().orElse(0);
        if (encoded.length > 15) throw new IllegalArgumentException("Interface names have at most 15 bytes");
        nativeSetDefaultNetwork(encoded, gateway);
    }

    /// Disconnects this node and closes its connections, keeping its saved identity for next time.
    /// Waits for initialization and shutdown to finish; wakes pending status and connection calls
    /// with [IllegalStateException]. Repeated or concurrent calls are safe.
    @WorkerThread
    @Override
    public void close() {
        nativeClose(handle);
    }

    private static void check(int result) throws IOException {
        switch (result) {
            case -1 -> throw new IllegalStateException("Node is closed");
            case -2 -> throw new IllegalStateException("Node is not started");
            case -3 -> throw new IllegalArgumentException("Invalid argument");
            case -4 -> throw new UnknownHostException("Tailnet DNS cannot resolve the host");
            case -5 -> throw new NoRouteToHostException("Address is not reachable through the tailnet");
            case -6 -> throw new SocketTimeoutException("Timed out");
            case -7 -> throw new ConnectException("Tailnet connection failed");
            case -8 -> throw new IOException("Logout failed");
            case -9 -> throw new IOException("Login failed");
            case -10 -> throw new IllegalStateException("Node was already started");
            case -11 -> throw new IOException("TS_AUTHKEY or TS_AUTH_KEY is set in the process environment");
            case -12 -> throw new IOException("Node failed to start");
            default -> {
                if (result < 0) throw new IOException("Native failure " + result);
            }
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

    private static native int nativeLogin(long handle);

    private static native void nativeClose(long handle);

    private static native String nativeTailscaleVersion();

    private static native void nativeSetDefaultNetwork(byte[] name, int gateway);
}
