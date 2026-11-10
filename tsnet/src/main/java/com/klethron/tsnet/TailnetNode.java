package com.klethron.tsnet;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.ParcelFileDescriptor;
import androidx.annotation.AnyThread;
import androidx.annotation.WorkerThread;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/// An embedded tsnet node: one device in a [tailnet](https://tailscale.com/kb/1136/tailnet), with
/// keys stored in a caller-owned directory. It opens outbound TCP connections through a userspace
/// network stack and does not route the Android device's traffic.
///
/// [Start][#start] a node, follow [#awaitStatus] until it is [NodeStatus.State#RUNNING] without a
/// [NodeStatus#loginUrl()], then [#dial]. Running nodes follow the device's default network to
/// reconnect after Wi-Fi or cellular changes; while another app's VPN is active, that network is the
/// VPN, which hides such changes. Methods are thread-safe; `@WorkerThread` methods block. Closing the
/// node ends its connections and cancels blocked calls, which then throw [ClosedChannelException].
/// Zero or negative timeouts do not wait: [#awaitStatus] returns at once, [#dial] and [#logout]
/// time out at once. The first use of this class in a process loads its native library.
public final class TailnetNode implements AutoCloseable {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    /// Reports the default network to all nodes in this process; registered while any node runs.
    private static final ConnectivityManager.NetworkCallback DEFAULT_NETWORK = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            String name = properties.getInterfaceName();
            int gateway = properties.getRoutes().stream()
                    .filter(route -> route.isDefaultRoute() && route.hasGateway() && route.getGateway() instanceof Inet4Address)
                    .mapToInt(route -> ByteBuffer.wrap(route.getGateway().getAddress()).getInt()).findFirst().orElse(0);
            nativeSetDefaultNetwork(name == null ? new byte[0] : name.getBytes(StandardCharsets.UTF_8), gateway);
        }

        @Override
        public void onLost(Network network) {
            nativeSetDefaultNetwork(new byte[0], 0);
        }
    };
    private static int running; // Guarded by DEFAULT_NETWORK, as is connectivity.
    private static @Nullable ConnectivityManager connectivity;

    private final long handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TailnetNode(long handle) {
        this.handle = handle;
    }

    /// Starts a node that connects to Tailscale, restoring a saved login or requesting browser
    /// authorization. Returns after local initialization; [#awaitStatus] reports progress and the
    /// login URL. The `context` gives access to [ConnectivityManager].
    ///
    /// The directory stores this node's private keys. Give each node its own directory under
    /// [Context#getNoBackupFilesDir()]; it is created if missing. Starting again with it after
    /// [#close] restores the same identity. Never back up or share its contents.
    ///
    /// @param hostname the requested device name; Tailscale may adjust it
    /// @throws IllegalArgumentException if the directory is relative or the hostname is empty or contains NUL
    /// @throws IOException if initialization fails
    @WorkerThread
    public static TailnetNode start(Context context, File stateDirectory, String hostname) throws IOException {
        return start(context, stateDirectory, hostname, "");
    }

    /// As [#start(Context, File, String)], with an alternative control server URL for tests.
    static TailnetNode start(Context context, File stateDirectory, String hostname, String controlUrl) throws IOException {
        if (!stateDirectory.isAbsolute() || hostname.isEmpty()) {
            throw new IllegalArgumentException("State directory must be absolute and hostname non-empty");
        }
        String config = stateDirectory.getPath() + '\0' + hostname + '\0' + controlUrl;
        synchronized (DEFAULT_NETWORK) {
            if (running == 0) {
                // Reports the current default network at once, on a framework thread.
                connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
                connectivity.registerDefaultNetworkCallback(DEFAULT_NETWORK);
            }
            running++;
        }
        long handle = -1;
        try {
            handle = nativeStart(config.getBytes(StandardCharsets.UTF_8));
        } finally {
            if (handle < 0) stopFollowingDefaultNetwork();
        }
        check(handle);
        return new TailnetNode(handle);
    }

    private static void stopFollowingDefaultNetwork() {
        synchronized (DEFAULT_NETWORK) {
            if (--running == 0 && connectivity != null) {
                connectivity.unregisterNetworkCallback(DEFAULT_NETWORK);
                nativeSetDefaultNetwork(new byte[0], 0); // Unknown until the next start reports it.
            }
        }
    }

    /// Waits for a status change, returning the latest snapshot when it changes or `timeout` elapses.
    /// Pass `null` to read immediately, or a previous snapshot from this node to wait. Intermediate
    /// changes may be skipped.
    ///
    /// @throws IllegalArgumentException if `previous` belongs to another node
    /// @throws ClosedChannelException if the node is or becomes closed
    @WorkerThread
    public NodeStatus awaitStatus(@Nullable NodeStatus previous, Duration timeout) throws ClosedChannelException {
        if (previous != null && previous.owner != handle) {
            throw new IllegalArgumentException("Status belongs to another node");
        }
        String record = nativeAwaitStatus(handle, previous == null ? -1 : previous.version, millis(timeout));
        if (record == null) {
            throw new ClosedChannelException();
        }
        return new NodeStatus(handle, record);
    }

    /// Opens a TCP connection to a tailnet address or
    /// [MagicDNS](https://tailscale.com/kb/1081/magicdns) name. `timeout` covers both waiting for
    /// [NodeStatus.State#RUNNING] and connecting. Only peers and subnet routes in this node's network
    /// map are reachable. A name without a tailnet DNS route can be looked up through the device's
    /// resolver, but the connection stays in the tailnet.
    ///
    /// The caller owns the returned blocking Unix stream socket. Use [java.io.FileInputStream] and
    /// [java.io.FileOutputStream] on its descriptor, or [android.system.Os]. End of stream leaves the
    /// connection writable, but a reset or failure also appears as end of stream. Closing the
    /// descriptor or shutting down its output ends the whole connection once written data is
    /// delivered, or resets it if received data is left unread. Closing the node ends all
    /// connections at once.
    ///
    /// @param host a tailnet IP address or MagicDNS name, at most 255 UTF-8 bytes
    /// @throws IllegalArgumentException if the host is invalid or the port is not 1–65535
    /// @throws ClosedChannelException if the node is or becomes closed
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
    /// @throws ClosedChannelException if the node is or becomes closed
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
    /// @throws ClosedChannelException if the node is or becomes closed
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

    /// Disconnects this node and closes its connections, keeping its saved identity for the next
    /// [#start]. Waits for shutdown; pending calls throw [ClosedChannelException]. Repeated or
    /// concurrent calls are safe.
    @WorkerThread
    @Override
    public void close() {
        nativeClose(handle);
        if (!closed.getAndSet(true)) stopFollowingDefaultNetwork();
    }

    /// Maps node.go's negative results to exceptions.
    private static void check(long result) throws IOException {
        if (result >= 0) return;
        switch ((int) result) {
            case -1 -> throw new ClosedChannelException();
            case -2 -> throw new IllegalArgumentException("Invalid argument");
            case -3 -> throw new UnknownHostException("Tailnet DNS cannot resolve the host");
            case -4 -> throw new NoRouteToHostException("Address is not reachable through the tailnet");
            case -5 -> throw new SocketTimeoutException("Timed out");
            case -6 -> throw new ConnectException("Tailnet connection failed");
            case -7 -> throw new IOException("Logout failed");
            case -8 -> throw new IOException("Login failed");
            case -9 -> throw new IOException("TS_AUTHKEY or TS_AUTH_KEY is set in the process environment");
            case -10 -> throw new IOException("Node failed to start");
            default -> throw new IOException("Native failure " + result);
        }
    }

    /// Clamps to 0–`Long.MAX_VALUE` milliseconds; [Duration#toMillis] would throw on overflow.
    private static long millis(Duration timeout) {
        if (timeout.isNegative()) return 0;
        return timeout.compareTo(Duration.ofMillis(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : timeout.toMillis();
    }

    private static native long nativeStart(byte[] config);

    private static native @Nullable String nativeAwaitStatus(long handle, long afterVersion, long timeoutMillis);

    private static native int nativeDial(long handle, byte[] host, int port, long timeoutMillis);

    private static native int nativeLogout(long handle, long timeoutMillis);

    private static native int nativeLogin(long handle);

    private static native void nativeClose(long handle);

    private static native String nativeTailscaleVersion();

    private static native void nativeSetDefaultNetwork(byte[] name, int gateway);
}
