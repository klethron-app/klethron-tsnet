package com.klethron.tsnet;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// An embedded [tailnet](https://tailscale.com/kb/1136/tailnet) device with caller-owned keys and
/// outbound TCP. [Start][#start], [#awaitStatus] until [NodeStatus.State#RUNNING] without a
/// [NodeStatus#loginUrl()], then [#connect]. Follows the best non-VPN network, even with another VPN active.
/// Methods are thread-safe except main-thread [#setLocalNetworkPermissionCallback]; `@WorkerThread`
/// methods block. [#close] cancels blocked calls with
/// [ClosedChannelException]. Non-positive timeouts return immediately from [#awaitStatus] and fail
/// [#connect] and [#logout]. Operations from a [StateStore] callback throw [IllegalStateException].
/// First use loads the native library.
public final class TailnetNode implements AutoCloseable {
    static {
        if (Timing.ENABLED) Timing.event("process", 0, 0, "native_load", "begin", "");
        System.loadLibrary("klethron_tsnet");
        if (Timing.ENABLED) {
            Timing.event("process", 0, 0, "native_load", "end", "");
            Timing.initialize();
            Timing.event("process", 0, 0, "certificates", "begin", "");
        }
        try {
            KeyStore roots = KeyStore.getInstance("AndroidCAStore");
            roots.load(null);
            ByteArrayOutputStream certificates = new ByteArrayOutputStream();
            for (var aliases = roots.aliases(); aliases.hasMoreElements();) {
                String alias = aliases.nextElement();
                var certificate = alias.startsWith("system:") ? roots.getCertificate(alias) : null;
                if (certificate != null) certificates.writeBytes(certificate.getEncoded());
            }
            nativeSetRoots(certificates.toByteArray());
            if (Timing.ENABLED) Timing.event("process", 0, 0, "certificates", "end", "ok");
        } catch (GeneralSecurityException | IOException failure) {
            if (Timing.ENABLED) Timing.event("process", 0, 0, "certificates", "end", "error");
            throw new ExceptionInInitializerError(failure);
        }
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
            report(this, (name + '\0' + gateway).getBytes(StandardCharsets.UTF_8), network.getNetworkHandle(), properties);
        }

        @Override
        public void onLost(Network network) {
            report(this, new byte[0], 0, null);
        }
    }

    private static synchronized void report(NetworkMonitor monitor, byte[] value, long handle, @Nullable LinkProperties properties) {
        // Unregistering cannot cancel a callback already dispatched on the main thread.
        if (network == monitor) {
            // Prefixes of the chosen physical link, excluding VPNs and unrelated interfaces.
            LocalNetworkPermission.report(properties == null ? List.of() : properties.getLinkAddresses().stream()
                    .map(address -> new IpPrefix(address.getAddress(), address.getPrefixLength())).toList());
            nativeSetDefaultNetwork(value, handle);
        }
    }
    /// Following a VPN would hide changes to the underlying network.
    static final NetworkRequest PHYSICAL_NETWORK = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
    private static final Set<StateStore> stores = Collections.newSetFromMap(new IdentityHashMap<>());
    private static @Nullable NetworkMonitor network;

    private final long handle;
    private final StateStore store;
    private final LocalNetworkPermission localNetworkPermission;
    private volatile boolean closed;

    private TailnetNode(long handle, StateStore store, LocalNetworkPermission localNetworkPermission) {
        this.handle = handle;
        this.store = store;
        this.localNetworkPermission = localNetworkPermission;
        if (Timing.ENABLED) {
            Timing.link("tsnet_handle", handle, "node", Timing.identity(this));
            Timing.link("node", Timing.identity(this), "store", Timing.identity(store));
        }
    }

    /// Starts with the saved login or browser authorization. Returns after local initialization;
    /// [#awaitStatus] reports progress and the login URL. `context` supplies [ConnectivityManager].
    ///
    /// The caller supplies exclusive, private [StateStore] storage. Reusing its persisted values
    /// restores the identity. This library does not create state files or directories.
    ///
    /// @param hostname the requested device name; Tailscale may adjust it
    /// @throws IllegalArgumentException if the hostname is empty or contains NUL
    /// @throws IllegalStateException if an open node already uses this store instance
    /// @throws IOException if initialization or storage fails
    @WorkerThread
    public static TailnetNode start(Context context, StateStore store, String hostname) throws IOException {
        return start(context, store, hostname, "");
    }

    static TailnetNode start(Context context, StateStore store, String hostname, String controlUrl) throws IOException {
        Store.checkNotInCallback();
        Objects.requireNonNull(store);
        if (hostname.isEmpty() || hostname.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Hostname must be non-empty without NUL");
        }
        synchronized (TailnetNode.class) {
            if (stores.contains(store)) throw new IllegalStateException("Another node uses this state store");
            if (stores.isEmpty()) network = new NetworkMonitor(context);
            stores.add(store);
        }
        long handle = -1;
        LocalNetworkPermission permission = new LocalNetworkPermission(
                Objects.requireNonNullElse(context.getApplicationContext(), context));
        if (Timing.ENABLED) Timing.event("store", Timing.identity(store), 0, "java_start", "begin", "");
        try {
            handle = nativeStart(new Store(store, permission), (hostname + '\0' + controlUrl).getBytes(StandardCharsets.UTF_8));
        } finally {
            if (Timing.ENABLED) Timing.event("store", Timing.identity(store), 0, "java_start", "end",
                    handle < 0 ? "error" : "ok");
            if (handle < 0) release(store);
        }
        check(handle);
        return new TailnetNode(handle, store, permission);
    }

    // Byte arrays preserve standard UTF-8 keys across JNI; only these callback names are kept by R8.
    private static final class Store {
        private static final ThreadLocal<Boolean> inCallback = new ThreadLocal<>();
        final StateStore delegate;
        final LocalNetworkPermission permission;

        Store(StateStore delegate, LocalNetworkPermission permission) {
            this.delegate = delegate;
            this.permission = permission;
        }

        private void localNetworkDenied(byte[] peer, byte[] destination) {
            permission.denied(peer, destination);
        }

        static void checkNotInCallback() {
            if (Boolean.TRUE.equals(inCallback.get())) {
                throw new IllegalStateException("Node operations are not allowed in state callbacks");
            }
        }

        private byte @Nullable [] read(byte[] key) throws IOException {
            inCallback.set(true);
            try {
                return delegate.read(new String(key, StandardCharsets.UTF_8));
            } finally {
                inCallback.remove();
            }
        }

        private void write(byte[] key, byte @Nullable [] value) throws IOException {
            inCallback.set(true);
            try {
                delegate.write(new String(key, StandardCharsets.UTF_8), value);
            } finally {
                inCallback.remove();
            }
        }
    }

    private static synchronized void release(StateStore store) {
        stores.remove(store);
        if (stores.isEmpty() && network != null) {
            NetworkMonitor previous = network;
            network = null;
            try {
                previous.connectivity.unregisterNetworkCallback(previous);
            } finally {
                LocalNetworkPermission.report(List.of());
                nativeSetDefaultNetwork(new byte[0], 0); // Unknown until the next start reports it.
            }
        }
    }

    /// Waits for a status change, returning the latest snapshot when it changes or `timeout` elapses.
    /// Pass `null` to read immediately, or a previous snapshot from this node to wait. Intermediate
    /// changes may be skipped. Returns `previous` itself if nothing changed.
    ///
    /// @throws IllegalArgumentException if `previous` belongs to another node
    /// @throws IOException if a storage callback failed
    /// @throws ClosedChannelException if the node is or becomes closed
    @WorkerThread
    public NodeStatus awaitStatus(@Nullable NodeStatus previous, Duration timeout) throws IOException {
        Store.checkNotInCallback();
        if (previous != null && previous.owner != handle) {
            throw new IllegalArgumentException("Status belongs to another node");
        }
        String record = nativeAwaitStatus(handle, previous == null ? -1 : previous.version, NANOSECONDS.convert(timeout));
        if (record == null) {
            throw new ClosedChannelException();
        }
        if (record.equals("!")) check(-11);
        return record.isEmpty() ? Objects.requireNonNull(previous) : new NodeStatus(handle, record);
    }

    /// Opens a TCP connection to a tailnet IP or [MagicDNS](https://tailscale.com/kb/1081/magicdns) name.
    /// Returns once connected; `timeout` covers local setup, waiting for [NodeStatus.State#RUNNING]
    /// and connecting. Only peers and subnet routes are reachable; names resolve through the tailnet.
    ///
    /// The caller owns and must close the returned blocking channel. Use [SocketChannel#socket()]
    /// for sequential request/response streams and read timeouts, or
    /// [SocketChannel#configureBlocking(boolean)] for selectors. For concurrent reads and writes,
    /// use the channel directly: Android's channel-backed streams share a lock.
    /// Its socket addresses describe a local bridge, not the tailnet peer; socket options apply only
    /// to that bridge. Both ends use `TCP_NODELAY`.
    ///
    /// Peer end of stream leaves the connection writable. Closing the channel or shutting down its
    /// output ends the connection after delivering written data; unread received data can cause a reset.
    /// Failures reset the connection, as does closing the node or losing tailnet access (leaving
    /// [NodeStatus.State#RUNNING] except for [NodeStatus.State#STARTING]). A gap in status notifications
    /// also ends existing connections because it can hide revocation or a change of login.
    /// A new login that changes the node key ends connections opened under the previous key.
    ///
    /// @param host a tailnet IP address or MagicDNS name, at most 255 UTF-8 bytes
    /// @throws IllegalArgumentException if the host is invalid or the port is not 1–65535
    /// @throws ClosedChannelException if the node is or becomes closed
    /// @throws UnknownHostException if the tailnet's DNS cannot resolve `host`
    /// @throws NoRouteToHostException if the address is outside the tailnet; it never falls back to a local connection
    /// @throws SocketTimeoutException if the timeout elapses first
    /// @throws ConnectException if the connection fails or tailnet access ends while connecting
    /// @throws IOException if the connection cannot be opened
    @WorkerThread
    public SocketChannel connect(String host, int port, Duration timeout) throws IOException {
        Store.checkNotInCallback();
        long timeoutNanos = NANOSECONDS.convert(timeout);
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > 255 || host.indexOf(0) >= 0 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Host must have 1–255 bytes without NUL and port must be 1–65535");
        }
        if (closed) throw new ClosedChannelException();
        if (timeoutNanos <= 0) throw new SocketTimeoutException("Timed out");
        long started = System.nanoTime();
        SocketChannel connection = SocketChannel.open();
        if (Timing.ENABLED) {
            Timing.link("node", Timing.identity(this), "channel", Timing.identity(connection));
            Timing.event("channel", Timing.identity(connection), 0, "java_connect", "begin", "");
        }
        try {
            try (ParcelFileDescriptor socket = localBridge(connection)) {
                if (Timing.ENABLED) Timing.link("channel", Timing.identity(connection), "fd", socket.getFd());
                check(nativeDial(handle, name, port, socket.detachFd(),
                        Math.max(0, timeoutNanos - (System.nanoTime() - started))));
            }
            if (Timing.ENABLED) Timing.event("channel", Timing.identity(connection), 0, "java_connect", "end", "ok");
            return connection;
        } catch (IOException | RuntimeException | Error failure) {
            if (Timing.ENABLED) Timing.event("channel", Timing.identity(connection), 0, "java_connect", "end", "error");
            try {
                connection.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /// Android cannot adopt a Unix socket into a SocketChannel. Keep the loopback pair private.
    private static ParcelFileDescriptor localBridge(SocketChannel connection) throws IOException {
        if (Timing.ENABLED) Timing.event("channel", Timing.identity(connection), 0, "loopback_bridge", "begin", "");
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            connection.socket().setTcpNoDelay(true);
            // Fail instead of blocking if another app fills the listener's queue.
            connection.configureBlocking(false);
            connection.connect(listener.getLocalAddress());
            listener.configureBlocking(false);
            try (SocketChannel accepted = listener.accept()) {
                // Another app can race the listener. Never give it a tailnet connection.
                // The client does not use SO_REUSEADDR, so its local address is unique.
                if (accepted == null || !accepted.getRemoteAddress().equals(connection.getLocalAddress())
                        || !connection.finishConnect()) {
                    throw new IOException("Local connection unavailable");
                }
                connection.configureBlocking(true);
                return ParcelFileDescriptor.fromSocket(accepted.socket());
            }
        } finally {
            if (Timing.ENABLED) Timing.event("channel", Timing.identity(connection), 0, "loopback_bridge", "end", "");
        }
    }

    /// Ends access and connections and deletes the saved login. The node stays open in
    /// [NodeStatus.State#NEEDS_LOGIN] and can [#login] again. Does nothing if already logged out.
    ///
    /// If logout fails, the node stops connecting but keeps its saved login: retry the logout.
    /// Clearing the store instead leaves the device registered in the tailnet. After a successful
    /// logout, [#close] and clear the store to also forget the remaining device key.
    /// The timeout covers queued authentication and network requests. Storage callbacks must finish
    /// before this method returns, even after the timeout.
    ///
    /// @throws ClosedChannelException if the node is or becomes closed
    /// @throws SocketTimeoutException if Tailscale does not confirm within `timeout`
    /// @throws IOException if the logout fails
    @WorkerThread
    public void logout(Duration timeout) throws IOException {
        Store.checkNotInCallback();
        check(nativeLogout(handle, NANOSECONDS.convert(timeout)));
    }

    /// Requests browser authorization after logout or expiry, or to renew a login. Returns before
    /// authorization; [#awaitStatus] supplies the [NodeStatus#loginUrl()]. Logged-in nodes keep working.
    /// [#start] requests the initial login automatically.
    /// Login and logout are serialized; this call waits for an earlier logout to finish.
    ///
    /// @throws ClosedChannelException if the node is or becomes closed
    /// @throws IOException if the login cannot be started
    @WorkerThread
    public void login() throws IOException {
        Store.checkNotInCallback();
        check(nativeLogin(handle));
    }

    /// Returns the bundled Tailscale version, including its source revision.
    @AnyThread
    public static String tailscaleVersion() {
        return nativeTailscaleVersion();
    }

    /// Offers local-network permission after a direct UDP attempt to `peer` fails with a permission
    /// error on the current physical network. Delivery is on the main thread, once per registration,
    /// and only when Android 17's `ACCESS_LOCAL_NETWORK` permission is missing for a target-37+ app.
    /// Discovery pings and WireGuard sends qualify; STUN, relays and other peers do not.
    ///
    /// Register only while the selected peer's terminal is visible and the user has not dismissed
    /// the offer. Pass `(null, null)` when it hides, the selection changes, or the offer is dismissed.
    /// Replacing or clearing a registration cancels queued delivery. The callback may show a rationale
    /// or request permission; the app owns its manifest permission, UI and dismissal persistence.
    /// Closing the node cancels pending delivery. Callback exceptions follow normal main-thread rules.
    @MainThread
    public void setLocalNetworkPermissionCallback(NodeStatus.@Nullable Peer peer, @Nullable Runnable callback) {
        localNetworkPermission.set(peer, callback);
    }

    /// Closes connections and waits for shutdown, keeping the identity for the next [#start].
    /// Pending calls throw [ClosedChannelException]. Repeated or concurrent calls are safe.
    @WorkerThread
    @Override
    public void close() {
        // Check before taking the lock: another close may already be waiting for this callback.
        Store.checkNotInCallback();
        synchronized (this) {
            if (closed) return;
            closed = true;
            localNetworkPermission.close();
            nativeClose(handle);
            release(store);
        }
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
            case -11 -> throw new IOException("State store failed; close the node before retrying");
            default -> throw new IOException("Native failure " + result);
        }
    }

    private static native long nativeStart(Store store, byte[] config);

    private static native void nativeSetRoots(byte[] certificates) throws GeneralSecurityException;

    private static native @Nullable String nativeAwaitStatus(long handle, long afterVersion, long timeoutNanos);

    private static native int nativeDial(long handle, byte[] host, int port, int socket, long timeoutNanos);

    private static native int nativeLogout(long handle, long timeoutNanos);

    private static native int nativeLogin(long handle);

    private static native void nativeClose(long handle);

    private static native void nativeSetDefaultNetwork(byte[] network, long handle);

    private static native String nativeTailscaleVersion();
}
