package com.klethron.tsnet;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
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
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// A device in a [tailnet](https://tailscale.com/kb/1136/tailnet), with caller-owned keys and outbound
/// TCP connections. [Start][#start], [#awaitStatus] until [NodeStatus.State#RUNNING] without a
/// [NodeStatus#loginUrl()], then [#dial]. Follows the best non-VPN network, even with another VPN active.
/// Methods are thread-safe; `@WorkerThread` methods block. Closing ends connections and cancels
/// blocked calls with [ClosedChannelException]. Zero or negative timeouts return immediately from
/// [#awaitStatus] and fail [#dial] and [#logout]. First use loads the native library.
public final class TailnetNode implements AutoCloseable {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private static final class NetworkMonitor extends ConnectivityManager.NetworkCallback {
        final ConnectivityManager connectivity;

        NetworkMonitor(Context context) {
            connectivity = Objects.requireNonNullElse(context.getApplicationContext(), context)
                    .getSystemService(ConnectivityManager.class);
            connectivity.registerBestMatchingNetworkCallback(PHYSICAL_NETWORK, this,
                    new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            String gateway = properties.getRoutes().stream()
                    .filter(route -> route.isDefaultRoute() && route.hasGateway() && route.getGateway() instanceof Inet4Address)
                    .map(route -> route.getGateway().getHostAddress()).findFirst().orElse("");
            String name = Objects.toString(properties.getInterfaceName(), "");
            report((name + '\0' + gateway).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onLost(Network network) {
            report(new byte[0]);
        }

        private void report(byte[] value) {
            synchronized (directories) {
                // Unregistering cannot cancel a callback already dispatched on the main thread.
                if (network == this) nativeSetDefaultNetwork(value);
            }
        }
    }
    /// Following a VPN would hide changes to the underlying network.
    static final NetworkRequest PHYSICAL_NETWORK = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
    /// Canonical state directories of running nodes. Also guards the active network registration.
    private static final Set<String> directories = new HashSet<>();
    private static @Nullable NetworkMonitor network;

    private final long handle;
    private final String directory;
    private boolean closed; // Guarded by this.

    private TailnetNode(long handle, String directory) {
        this.handle = handle;
        this.directory = directory;
    }

    /// Starts a node with its saved login or browser authorization. Returns after local initialization;
    /// [#awaitStatus] reports progress and the login URL. `context` supplies [ConnectivityManager].
    ///
    /// The directory stores this node's private keys. Give each node its own directory under
    /// [Context#getNoBackupFilesDir()]; it is created if missing, with symlinks resolved before startup.
    /// Starting again with it after [#close] restores the same identity. Never back up or share its
    /// contents, and never use it from two processes at once.
    ///
    /// @param hostname the requested device name; Tailscale may adjust it
    /// @throws IllegalArgumentException if the directory is relative or the hostname is empty or contains NUL
    /// @throws IllegalStateException if an unclosed node in this process uses the directory
    /// @throws IOException if initialization fails
    @WorkerThread
    public static TailnetNode start(Context context, File stateDirectory, String hostname) throws IOException {
        return start(context, stateDirectory, hostname, "");
    }

    /// As [#start(Context, File, String)], with an alternative control server URL for tests.
    static TailnetNode start(Context context, File stateDirectory, String hostname, String controlUrl) throws IOException {
        if (!stateDirectory.isAbsolute() || hostname.isEmpty() || hostname.indexOf(0) >= 0) {
            throw new IllegalArgumentException("State directory must be absolute and hostname non-empty without NUL");
        }
        String directory = stateDirectory.getCanonicalPath();
        String config = directory + '\0' + hostname + '\0' + controlUrl;
        synchronized (directories) {
            if (directories.isEmpty()) {
                network = new NetworkMonitor(context);
            }
            if (!directories.add(directory)) {
                throw new IllegalStateException("Another node in this process uses " + stateDirectory);
            }
        }
        long handle = -1;
        try {
            handle = nativeStart(config.getBytes(StandardCharsets.UTF_8));
        } finally {
            if (handle < 0) release(directory);
        }
        check(handle);
        return new TailnetNode(handle, directory);
    }

    private static void release(String directory) {
        synchronized (directories) {
            directories.remove(directory);
            if (directories.isEmpty() && network != null) {
                NetworkMonitor previous = network;
                network = null;
                try {
                    previous.connectivity.unregisterNetworkCallback(previous);
                } finally {
                    nativeSetDefaultNetwork(new byte[0]); // Unknown until the next start reports it.
                }
            }
        }
    }

    /// Waits for a status change, returning the latest snapshot when it changes or `timeout` elapses.
    /// Pass `null` to read immediately, or a previous snapshot from this node to wait. Intermediate
    /// changes may be skipped. Returns `previous` itself if nothing changed.
    ///
    /// @throws IllegalArgumentException if `previous` belongs to another node
    /// @throws ClosedChannelException if the node is or becomes closed
    @WorkerThread
    public NodeStatus awaitStatus(@Nullable NodeStatus previous, Duration timeout) throws ClosedChannelException {
        if (previous != null && previous.owner != handle) {
            throw new IllegalArgumentException("Status belongs to another node");
        }
        String record = nativeAwaitStatus(handle, previous == null ? -1 : previous.version, NANOSECONDS.convert(timeout));
        if (record == null) {
            throw new ClosedChannelException();
        }
        return record.isEmpty() ? Objects.requireNonNull(previous) : new NodeStatus(handle, record);
    }

    /// Opens a tailnet TCP connection as a caller-owned blocking Unix stream socket for file streams
    /// or [android.system.Os]. See [#dial(String, int, ParcelFileDescriptor, Duration)] for routing,
    /// timeouts, shutdown, NIO selectors and distinguishing resets from end of stream.
    ///
    /// @throws IOException if the connection cannot be opened
    @WorkerThread
    public ParcelFileDescriptor dial(String host, int port, Duration timeout) throws IOException {
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        try {
            dial(host, port, pair[1], timeout);
            return pair[0];
        } catch (IOException | RuntimeException | Error failure) {
            try {
                pair[0].close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /// Relays TCP between `socket` and a tailnet IP or [MagicDNS](https://tailscale.com/kb/1081/magicdns)
    /// name. `timeout` covers waiting for [NodeStatus.State#RUNNING] and connecting. Only network-map
    /// peers and subnet routes are reachable; names resolve through the map or tailnet DNS settings.
    ///
    /// The node takes ownership of this connected TCP or Unix stream socket, even if dialing fails.
    /// The caller uses the other end: a loopback TCP pair for [java.nio.channels.SocketChannel]
    /// selectors, or [ParcelFileDescriptor#createSocketPair()] for file streams. Peer end of stream
    /// leaves the connection writable. Closing the caller's end or shutting down its output ends
    /// the connection after delivering written data; unread received data can cause a reset.
    /// Failures reset the connection, as does closing the node or losing tailnet access (leaving
    /// [NodeStatus.State#RUNNING] except for [NodeStatus.State#STARTING]). Unix sockets read resets
    /// as end of stream unless data written by the caller is still unread by the relay.
    ///
    /// @param host a tailnet IP address or MagicDNS name, at most 255 UTF-8 bytes
    /// @throws IllegalArgumentException if the host is invalid, the port is not 1–65535, or `socket` is
    /// not a connected stream socket
    /// @throws ClosedChannelException if the node is or becomes closed
    /// @throws UnknownHostException if the tailnet's DNS cannot resolve `host`
    /// @throws NoRouteToHostException if the address is outside the tailnet; it never falls back to a local connection
    /// @throws SocketTimeoutException if the timeout elapses first
    /// @throws ConnectException if the connection fails or tailnet access ends while connecting
    @WorkerThread
    public void dial(String host, int port, ParcelFileDescriptor socket, Duration timeout) throws IOException {
        try (socket) { // Closes it unless passed on.
            long timeoutNanos = NANOSECONDS.convert(timeout);
            byte[] name = host.getBytes(StandardCharsets.UTF_8);
            if (name.length == 0 || name.length > 255 || host.indexOf(0) >= 0 || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Host must have 1–255 bytes without NUL and port must be 1–65535");
            }
            check(nativeDial(handle, name, port, socket.detachFd(), timeoutNanos));
        }
    }

    /// Ends tailnet access and connections and deletes the saved login. The node stays open in
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
        check(nativeLogout(handle, NANOSECONDS.convert(timeout)));
    }

    /// Requests browser authorization after logout or key expiry, or to renew a login. Returns
    /// before authorization; [#awaitStatus] supplies the [NodeStatus#loginUrl()]. Logged-in nodes keep
    /// working during authorization. [#start] requests the initial login automatically.
    ///
    /// @throws ClosedChannelException if the node is or becomes closed
    /// @throws IOException if the login cannot be started
    @WorkerThread
    public void login() throws IOException {
        check(nativeLogin(handle));
    }

    /// Returns the bundled Tailscale version, including its source revision.
    @AnyThread
    public static native String tailscaleVersion();

    /// Disconnects this node and closes its connections, keeping its saved identity for the next
    /// [#start]. Waits for shutdown; pending calls throw [ClosedChannelException]. Repeated or
    /// concurrent calls are safe.
    @WorkerThread
    @Override
    public synchronized void close() {
        // Serialized, so no call returns before the directory is free.
        if (closed) return;
        nativeClose(handle);
        closed = true;
        release(directory);
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

    private static native long nativeStart(byte[] config);

    private static native @Nullable String nativeAwaitStatus(long handle, long afterVersion, long timeoutNanos);

    private static native int nativeDial(long handle, byte[] host, int port, int socket, long timeoutNanos);

    private static native int nativeLogout(long handle, long timeoutNanos);

    private static native int nativeLogin(long handle);

    private static native void nativeClose(long handle);

    private static native void nativeSetDefaultNetwork(byte[] network);
}
