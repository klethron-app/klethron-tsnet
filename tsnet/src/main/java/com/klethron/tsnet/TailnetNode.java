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

/// An embedded tsnet node: one device in a [tailnet](https://tailscale.com/kb/1136/tailnet), with
/// keys stored in a caller-owned directory. It opens outbound TCP connections through a userspace
/// network stack and does not route the Android device's traffic.
///
/// [Start][#start] a node, follow [#awaitStatus] until it is [NodeStatus.State#RUNNING] without a
/// [NodeStatus#loginUrl()], then [#dial]. Running nodes follow the device's best Wi-Fi, cellular or
/// other non-VPN internet network to reconnect after it changes, also while another app's VPN is
/// active. Methods are thread-safe; `@WorkerThread` methods block. Closing the node ends its
/// connections and cancels blocked calls, which then throw [ClosedChannelException]. Zero or
/// negative timeouts do not wait: [#awaitStatus] returns at once, [#dial] and [#logout] time out at
/// once. The first use of this class in a process loads its native library.
public final class TailnetNode implements AutoCloseable {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    /// Reports the network to all nodes in this process; registered while any node runs.
    private static final ConnectivityManager.NetworkCallback NETWORK = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            String gateway = properties.getRoutes().stream()
                    .filter(route -> route.isDefaultRoute() && route.hasGateway() && route.getGateway() instanceof Inet4Address)
                    .map(route -> route.getGateway().getHostAddress()).findFirst().orElse("");
            String name = Objects.toString(properties.getInterfaceName(), "");
            nativeSetDefaultNetwork((name + '\0' + gateway).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onLost(Network network) {
            nativeSetDefaultNetwork(new byte[0]);
        }
    };
    /// Another app's VPN, often a split tunnel that the node's traffic bypasses, would be the default
    /// network and hide changes of the network underneath.
    static final NetworkRequest PHYSICAL_NETWORK = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
    /// Canonical state directories of running nodes. Guarded by NETWORK, as is connectivity.
    private static final Set<String> directories = new HashSet<>();
    private static @Nullable ConnectivityManager connectivity;

    private final long handle;
    private final String directory;
    private boolean closed; // Guarded by this.

    private TailnetNode(long handle, String directory) {
        this.handle = handle;
        this.directory = directory;
    }

    /// Starts a node that connects to Tailscale, restoring a saved login or requesting browser
    /// authorization. Returns after local initialization; [#awaitStatus] reports progress and the
    /// login URL. The `context` gives access to [ConnectivityManager].
    ///
    /// The directory stores this node's private keys. Give each node its own directory under
    /// [Context#getNoBackupFilesDir()]; it is created if missing. Starting again with it after
    /// [#close] restores the same identity. Never back up or share its contents, and never use it
    /// from two processes at once.
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
        String config = stateDirectory.getPath() + '\0' + hostname + '\0' + controlUrl;
        String directory = stateDirectory.getCanonicalPath();
        synchronized (NETWORK) {
            if (directories.isEmpty()) {
                // Registering reports the current network at once.
                connectivity = Objects.requireNonNullElse(context.getApplicationContext(), context)
                        .getSystemService(ConnectivityManager.class);
                connectivity.registerBestMatchingNetworkCallback(PHYSICAL_NETWORK, NETWORK,
                        new Handler(Looper.getMainLooper()));
            }
            // Both nodes would save their own login state over the other's.
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

    /// Frees the directory, and stops following the network once no node runs.
    private static void release(String directory) {
        synchronized (NETWORK) {
            directories.remove(directory);
            if (directories.isEmpty() && connectivity != null) {
                connectivity.unregisterNetworkCallback(NETWORK);
                nativeSetDefaultNetwork(new byte[0]); // Unknown until the next start reports it.
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

    /// Opens a tailnet TCP connection and returns a blocking Unix stream socket owned by the caller.
    /// Read and write its descriptor with file streams or [android.system.Os], and close it when
    /// finished. See [#dial(String, int, ParcelFileDescriptor, Duration)] for routing, timeout and
    /// shutdown behavior. Use that overload for NIO selectors or to distinguish a reset from a
    /// peer's end of stream.
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

    /// Opens a TCP connection to a tailnet address or
    /// [MagicDNS](https://tailscale.com/kb/1081/magicdns) name and relays it onto `socket`. `timeout`
    /// covers both waiting for [NodeStatus.State#RUNNING] and connecting. Only peers and subnet routes
    /// in this node's network map are reachable. Names resolve only through the tailnet: peers' names
    /// from the network map, others through the tailnet's DNS settings. The device's resolver never
    /// sees them.
    ///
    /// `socket` is a connected TCP or Unix stream socket. The node owns it from the call on, closing it
    /// if the dial fails, and the caller uses its other end: of a loopback TCP pair for
    /// [java.nio.channels.SocketChannel] selectors, which do not support Unix sockets, or of
    /// [ParcelFileDescriptor#createSocketPair()] for file streams. End of stream means that the peer
    /// closed its side, leaving the connection writable. Closing the caller's end or shutting down its
    /// output ends the whole connection once written data is delivered, or can reset it if received
    /// data is left unread. The connection resets if it fails, and all connections reset at once when
    /// the node closes or loses tailnet access: when it leaves [NodeStatus.State#RUNNING] for a state
    /// other than [NodeStatus.State#STARTING], as on logout, key expiry or device removal. A Unix
    /// socket reads a reset as end of stream unless data written to it is unread.
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

    /// Ends this node's tailnet access and connections, and deletes its saved login. The node stays
    /// open, reports [NodeStatus.State#NEEDS_LOGIN] and can [#login] again. Does nothing if already
    /// logged out.
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
    public static native String tailscaleVersion();

    /// Disconnects this node and closes its connections, keeping its saved identity for the next
    /// [#start]. Waits for shutdown; pending calls throw [ClosedChannelException]. Repeated or
    /// concurrent calls are safe.
    @WorkerThread
    @Override
    public synchronized void close() {
        // Serialized, so no call returns before the directory is free.
        nativeClose(handle);
        if (!closed) release(directory);
        closed = true;
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
