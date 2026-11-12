package com.klethron.tsnet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.net.InetAddresses;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Dials an in-memory peer through the public API. A loopback test control server admits nodes
 * without login and serves MagicDNS; no real tailnet is involved.
 */
public final class DialTest {
    private static final Duration WAIT = LoginTest.WAIT;
    private static final String PEER_NAME = "klethron-peer.tail-scale.ts.net";

    private String controlUrl;
    private Path directory;
    private String peer;

    @Before
    public void startPeer() throws Exception {
        controlUrl = Probe.call("startControl", false);
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        directory = LoginTest.temporaryDirectory("dial-");
        peer = Probe.call("startPeer", directory.resolve("peer"));
        assertTrue("Peer failed to start", peer.startsWith("100."));
    }

    @After
    public void stopPeer() throws Exception {
        Probe.call("stopControl");
        LoginTest.deleteDirectory(directory);
    }

    @Test(timeout = 90000)
    public void echoesThroughTheTailnet() throws Exception {
        // Over a Unix socket pair. The dial waits for the node to join; listsPeersAndTheirChanges
        // dials by name.
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        try (TailnetNode node = startNode(); ParcelFileDescriptor connection = pair[0]) {
            node.dial(peer, 7070, pair[1], WAIT);
            byte[] sent = new byte[1 << 20];
            new Random().nextBytes(sent);
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try {
                    new FileOutputStream(connection.getFileDescriptor()).write(sent);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            byte[] received = new FileInputStream(connection.getFileDescriptor()).readNBytes(sent.length);
            writer.get(30, TimeUnit.SECONDS);
            assertArrayEquals(sent, received);
        }
    }

    @Test(timeout = 90000)
    public void listsPeersAndTheirChanges() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = dial(node, PEER_NAME, 7070, WAIT)) {
            NodeStatus status = LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING && !s.peers().isEmpty());
            assertEquals(1, status.peers().size());
            NodeStatus.Peer listed = status.peers().get(0);
            assertEquals(PEER_NAME, listed.dnsName());
            assertEquals("android", listed.os());
            assertFalse(listed.online());
            assertEquals(InetAddresses.parseNumericAddress(peer), listed.addresses().get(0));
            assertEquals(2, listed.addresses().size());
            assertEquals(List.of(), listed.sshHostKeys());
            // testcontrol's names; the peer registered first.
            assertEquals(List.of("klethron-dialer.tail-scale.ts.net", "fake-control.example.net", "user-2@fake-control.example.net"),
                    Arrays.asList(status.dnsName(), status.tailnet(), status.loginName()));
            assertTrue(status.toString(), status.toString().endsWith(", peers=1}"));
            assertFalse(status.toString().contains("tail-scale") || status.toString().contains("example.net"));
            connection.write(ByteBuffer.wrap(new byte[] {1}));
            ByteBuffer one = ByteBuffer.allocate(1);
            assertEquals(1, connection.read(one));
            assertEquals(1, one.get(0));

            // Hides the sharee and Mullvad nodes; sorts by name, lists IPv4 first, trims host keys and
            // drops one with a comma.
            Probe.call("addPeers");
            NodeStatus.Peer other = new NodeStatus.Peer("klethron-other.tail-scale.ts.net", "linux", false, List.of(
                    InetAddresses.parseNumericAddress("100.101.0.1"), InetAddresses.parseNumericAddress("fd7a:115c:a1e0::101:1")),
                    List.of("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"));
            assertEquals(List.of(other, listed), LoginTest.await(node, s -> s.peers().size() > 1).peers());
            assertEquals("true", Probe.call("setPeerOnline"));
            NodeStatus.Peer online = new NodeStatus.Peer(PEER_NAME, "android", true, listed.addresses(), List.of());
            assertEquals(List.of(other, online), LoginTest.await(node, s -> !s.peers().contains(listed)).peers());
            // The other peer has no endpoints: connecting, not waiting to run, times out.
            assertDialThrows(SocketTimeoutException.class, node, "100.101.0.1", 22, Duration.ofSeconds(1));

            node.logout(WAIT);
            // The peers and names clear after the state changes.
            status = LoginTest.await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN && s.peers().isEmpty());
            assertEquals(Arrays.asList(null, null, null), Arrays.asList(status.dnsName(), status.tailnet(), status.loginName()));
            // Logging out reset the connection, and new dials wait for a login.
            assertReset(connection);
            assertDialThrows(SocketTimeoutException.class, node, peer, 7070, Duration.ofMillis(500));
        }
    }

    @Test(timeout = 60000)
    public void resetsConnectionsWhenTheKeyExpires() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = dial(node, peer, 7070, WAIT)) {
            assertEquals("2", Probe.call("expire"));
            assertReset(connection);
        }
    }

    @Test(timeout = 60000)
    public void deliversPeerEndOfStreamAndClosesWithSocket() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = dial(node, peer, 7071, WAIT)) {
            // The peer greets and closes its side; this side remains writable.
            assertEquals("klethron\n", new String(Channels.newInputStream(connection).readAllBytes(), StandardCharsets.US_ASCII));
            connection.write(ByteBuffer.wrap("after".getBytes(StandardCharsets.US_ASCII)));
            connection.close();
            // Closing the socket ends the peer's connection after delivering what was written.
            assertEquals("5", Probe.call("awaitReceived"));
        }
    }

    @Test(timeout = 60000)
    public void servesANonBlockingConsumer() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = dial(node, peer, 7070, WAIT);
                Selector selector = Selector.open()) {
            connection.configureBlocking(false).register(selector, SelectionKey.OP_READ);
            ByteBuffer one = ByteBuffer.allocate(1);
            assertEquals(0, connection.read(one));
            // Interactive round trips of one byte each.
            for (int i = 0; i < 200; i++) {
                assertEquals(1, connection.write(ByteBuffer.wrap(new byte[] {(byte) i})));
                assertTrue("Not readable within 10 s", ready(selector, 10000));
                one.clear();
                assertEquals(1, connection.read(one));
                assertEquals((byte) i, one.get(0));
            }
        }
    }

    @Test(timeout = 120000)
    public void slowsAPeerWhileTheAppDoesNotRead() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = dial(node, peer, 7073, WAIT)) {
            long flooded = awaitFloodStalled();
            assertTrue("Peer was not slowed: " + flooded, flooded < FLOOD);
            ByteBuffer buffer = ByteBuffer.allocate(64 << 10);
            byte[] expected = new byte[buffer.capacity()];
            long received = 0;
            for (int read; (read = connection.read(buffer)) > 0; received += read) {
                assertTrue("Unexpected bytes after " + received,
                        Arrays.equals(buffer.array(), 0, read, pattern(received, expected), 0, read));
                buffer.clear();
            }
            assertEquals(FLOOD, received);
        }
    }

    @Test(timeout = 120000)
    public void slowsTheAppWhileThePeerDoesNotRead() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = dial(node, peer, 7074, WAIT);
                Selector selector = Selector.open()) {
            connection.configureBlocking(false).register(selector, SelectionKey.OP_WRITE);
            byte[] chunk = new byte[64 << 10];
            long written = 0;
            // Until the socket stays unwritable for a second.
            while (written < FLOOD && ready(selector, 1000)) {
                written += connection.write(ByteBuffer.wrap(pattern(written, chunk), 0, (int) Math.min(chunk.length, FLOOD - written)));
            }
            assertTrue("App was not slowed: " + written, written < FLOOD);
            Probe.call("release");
            while (written < FLOOD) {
                assertTrue("Not writable within 10 s", ready(selector, 10000));
                written += connection.write(ByteBuffer.wrap(pattern(written, chunk), 0, (int) Math.min(chunk.length, FLOOD - written)));
            }
            connection.close();
            assertEquals(FLOOD, Long.parseLong(Probe.call("awaitReceived")));
        }
    }

    @Test(timeout = 60000)
    public void resetsAnUnreadConnectionWithNode() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = dial(node, peer, 7073, WAIT);
                ParcelFileDescriptor observed = ParcelFileDescriptor.fromSocket(connection.socket())) {
            assertTrue("Peer was not slowed", awaitFloodStalled() < FLOOD);
            node.close();
            // With unread data, closing only the tailnet socket leaves the relay blocked.
            assertTrue("Relay stayed open", poll(observed.getFileDescriptor(), 0, 10000));
            // The first write reports the reset, the next one a broken pipe without SIGPIPE.
            assertEquals("Connection reset by peer", assertThrows(IOException.class,
                    () -> connection.write(ByteBuffer.wrap(new byte[] {1}))).getMessage());
            assertEquals("Broken pipe", assertThrows(IOException.class,
                    () -> connection.write(ByteBuffer.wrap(new byte[] {1}))).getMessage());
        }
    }

    @Test(timeout = 60000)
    public void reachesOnlyTheTailnet() throws Exception {
        try (TailnetNode node = startNode()) {
            dial(node, peer, 7070, WAIT).close(); // Joined.
            // Upstream would dial both through the device's network: the first is the control server.
            int controlPort = URI.create(controlUrl).getPort();
            assertDialThrows(NoRouteToHostException.class, node, "127.0.0.1", controlPort, WAIT);
            assertDialThrows(NoRouteToHostException.class, node, "100.64.0.99", 22, WAIT);
            // The device resolves this name, but the tailnet's DNS does not; upstream would ask the device.
            assertDialThrows(UnknownHostException.class, node, "localhost", controlPort, WAIT);
            assertDialThrows(UnknownHostException.class, node, "no-such-peer.tail-scale.ts.net", 22, WAIT);
            // A peer's short name still resolves from the network map.
            dial(node, "klethron-peer", 7070, WAIT).close();
            assertDialThrows(ConnectException.class, node, peer, 7072, WAIT);
        }
    }

    /// Dials onto one end of a loopback TCP pair, as for NIO selectors, and returns the other, blocking end.
    static SocketChannel dial(TailnetNode node, String host, int port, Duration timeout) throws IOException {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            SocketChannel connection = SocketChannel.open(listener.getLocalAddress());
            try (SocketChannel accepted = listener.accept()) {
                node.dial(host, port, ParcelFileDescriptor.fromSocket(accepted.socket()), timeout);
                return connection;
            } catch (IOException | RuntimeException e) {
                connection.close();
                throw e;
            }
        }
    }

    /// Dials onto one end of a Unix socket pair, requiring the dial to throw `expected` and close that end.
    @CanIgnoreReturnValue
    static <T extends Throwable> T assertDialThrows(Class<T> expected, TailnetNode node, String host, int port, Duration timeout) {
        try {
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
            try (ParcelFileDescriptor connection = pair[0]) {
                T thrown = assertThrows(expected, () -> node.dial(host, port, pair[1], timeout));
                assertEquals(-1, new FileInputStream(connection.getFileDescriptor()).read());
                return thrown;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Asserts that reading `connection` reports a reset, as when the node loses tailnet access.
    private static void assertReset(SocketChannel connection) {
        assertEquals("Connection reset by peer",
                assertThrows(IOException.class, () -> connection.read(ByteBuffer.allocate(1))).getMessage());
    }

    private TailnetNode startNode() throws IOException {
        return TailnetNode.start(LoginTest.CONTEXT, directory.resolve("node").toFile(), "klethron-dialer", controlUrl);
    }

    /// Waits until the peer on port 7073 has written and then made no progress for a second.
    private static long awaitFloodStalled() throws InterruptedException {
        long flooded = 0;
        for (long last = 0; flooded == 0 || flooded != last; Thread.sleep(1000)) {
            last = flooded;
            flooded = Long.parseLong(Probe.call("flooded"));
        }
        return flooded;
    }

    /// login_probe.go's floodBytes: bytes of the probe pattern (byte `i` is `i % 251`) on ports 7073 and 7074.
    private static final long FLOOD = 48 << 20;

    /// Fills `bytes` with the probe pattern from stream offset `offset`.
    private static byte[] pattern(long offset, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((offset + i) % 251);
        }
        return bytes;
    }

    /// Returns whether `fd` became ready for `events` within `timeoutMillis`.
    private static boolean poll(FileDescriptor fd, int events, int timeoutMillis) throws ErrnoException {
        StructPollfd poll = new StructPollfd();
        poll.fd = fd;
        poll.events = (short) events;
        return Os.poll(new StructPollfd[] {poll}, timeoutMillis) == 1;
    }

    /// Returns whether the selector's only channel became ready within `timeoutMillis`.
    private static boolean ready(Selector selector, int timeoutMillis) throws IOException {
        boolean ready = selector.select(timeoutMillis) == 1;
        selector.selectedKeys().clear();
        return ready;
    }
}
