package com.klethron.tsnet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.net.InetAddresses;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.StructPollfd;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// Dials a local peer through the public API; loopback control admits nodes and serves MagicDNS.
public final class DialTest {
    private static final Duration WAIT = LoginTest.WAIT;
    private static final String PEER_NAME = "klethron-peer.tail-scale.ts.net";

    private String controlUrl;
    private String peer;

    @Before
    public void startPeer() {
        controlUrl = Probe.call("startControl", false);
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        peer = Probe.call("startPeer");
        assertTrue("Peer failed to start", peer.startsWith("100."));
    }

    @After
    public void stopPeer() {
        Probe.call("stopControl");
    }

    @Test(timeout = 90000)
    public void echoesThroughTheTailnet() throws Exception {
        try (TailnetNode node = startNode()) {
            byte[] sent = new byte[1 << 20];
            new Random().nextBytes(sent);
            NodeStatus status = LoginTest.await(node, s -> !s.peers().isEmpty());
            for (InetAddress address : status.peers().get(0).addresses()) {
                try (SocketChannel connection = node.connect(address.getHostAddress(), 7070, WAIT)) {
                    assertTrue(connection.isConnected());
                    assertTrue(connection.isBlocking());
                    assertTrue(connection.socket().getTcpNoDelay());
                    connection.socket().setSoTimeout(50);
                    var input = connection.socket().getInputStream();
                    assertThrows(SocketTimeoutException.class, input::read);
                    connection.socket().setSoTimeout(30000);
                    // Android's channel-backed streams share a lock: use them sequentially.
                    connection.socket().getOutputStream().write(new byte[] {1, 2, 3});
                    assertArrayEquals(new byte[] {1, 2, 3}, input.readNBytes(3));
                    CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                        try {
                            ByteBuffer data = ByteBuffer.wrap(sent);
                            while (data.hasRemaining()) connection.write(data);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    ByteBuffer received = ByteBuffer.allocate(sent.length);
                    while (received.hasRemaining()) assertTrue(connection.read(received) > 0);
                    writer.get(30, TimeUnit.SECONDS);
                    assertArrayEquals(sent, received.array());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void listsPeersAndTheirChanges() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = node.connect(PEER_NAME, 7070, WAIT)) {
            NodeStatus status = LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING && s.peers().size() == 1);
            NodeStatus.Peer listed = status.peers().get(0);
            assertEquals(PEER_NAME, listed.dnsName());
            assertEquals("android", listed.operatingSystem());
            assertFalse(listed.online());
            assertEquals(InetAddresses.parseNumericAddress(peer), listed.addresses().get(0));
            assertEquals(2, listed.addresses().size());
            assertEquals(List.of(), listed.sshHostKeys());
            // testcontrol's names; the peer registered first.
            assertEquals(List.of("klethron-dialer.tail-scale.ts.net", "fake-control.example.net", "user-2@fake-control.example.net"),
                    Arrays.asList(status.dnsName(), status.tailnetName(), status.loginName()));
            assertTrue(status.toString(), status.toString().endsWith(", peers=1}"));
            assertFalse(status.toString().contains("tail-scale") || status.toString().contains("example.net"));
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
            assertEquals(Arrays.asList(null, null, null), Arrays.asList(status.dnsName(), status.tailnetName(), status.loginName()));
            assertReset(connection);
            assertDialThrows(SocketTimeoutException.class, node, peer, 7070, Duration.ofMillis(500));
        }
    }

    @Test(timeout = 60000)
    public void resetsConnectionsWhenTheKeyExpires() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = node.connect(peer, 7070, WAIT)) {
            assertEquals("2", Probe.call("expire"));
            assertReset(connection);
        }
    }

    @Test(timeout = 60000)
    public void logoutCancelsPendingConnections() throws Exception {
        try (TailnetNode node = startNode()) {
            Probe.call("addPeers");
            LoginTest.await(node, status -> status.state() == NodeStatus.State.RUNNING
                    && status.peers().stream().anyMatch(device -> device.dnsName().startsWith("klethron-other.")));
            // This peer is in the network map but never answers, so connecting would take all of WAIT.
            CountDownLatch dialing = new CountDownLatch(1);
            CompletableFuture<ConnectException> pending = CompletableFuture.supplyAsync(() -> {
                dialing.countDown();
                return assertDialThrows(ConnectException.class, node, "100.101.0.1", 22, WAIT);
            });
            assertTrue("Dial task did not start", dialing.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertFalse("Connection did not wait for the peer", pending.isDone());
            node.logout(WAIT);
            pending.get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 60000)
    public void deliversPeerEndOfStreamAndClosesWithSocket() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = node.connect(peer, 7071, WAIT)) {
            assertEquals("klethron\n", new String(Channels.newInputStream(connection).readAllBytes(), StandardCharsets.US_ASCII));
            connection.write(ByteBuffer.wrap("after".getBytes(StandardCharsets.US_ASCII)));
            connection.close();
            assertEquals("5", Probe.call("awaitReceived"));
        }
    }

    @Test(timeout = 60000)
    public void servesANonBlockingConsumer() throws Exception {
        try (TailnetNode node = startNode(); SocketChannel connection = node.connect(peer, 7070, WAIT);
                Selector selector = Selector.open()) {
            connection.configureBlocking(false).register(selector, SelectionKey.OP_READ);
            ByteBuffer one = ByteBuffer.allocate(1);
            assertEquals(0, connection.read(one));
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
        try (TailnetNode node = startNode(); SocketChannel connection = node.connect(peer, 7073, WAIT)) {
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
        try (TailnetNode node = startNode(); SocketChannel connection = node.connect(peer, 7074, WAIT);
                Selector selector = Selector.open()) {
            connection.configureBlocking(false).register(selector, SelectionKey.OP_WRITE);
            byte[] chunk = new byte[64 << 10];
            long written = 0;
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
        try (TailnetNode node = startNode(); SocketChannel connection = node.connect(peer, 7073, WAIT);
                ParcelFileDescriptor observed = ParcelFileDescriptor.fromSocket(connection.socket())) {
            assertTrue("Peer was not slowed", awaitFloodStalled() < FLOOD);
            node.close();
            // With unread data, closing only the tailnet socket leaves the relay blocked.
            StructPollfd poll = new StructPollfd();
            poll.fd = observed.getFileDescriptor();
            assertEquals("Relay stayed open", 1, Os.poll(new StructPollfd[] {poll}, 10000));
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
            node.connect(peer, 7070, WAIT).close(); // Joined.
            assertDialThrows(SocketTimeoutException.class, node, peer, 7070, Duration.ZERO);
            // Upstream would dial both through the device's network: the first is the control server.
            int controlPort = URI.create(controlUrl).getPort();
            assertDialThrows(NoRouteToHostException.class, node, "127.0.0.1", controlPort, WAIT);
            assertDialThrows(NoRouteToHostException.class, node, "100.64.0.99", 22, WAIT);
            // The device resolves this name, but the tailnet's DNS does not; upstream would ask the device.
            assertDialThrows(UnknownHostException.class, node, "localhost", controlPort, WAIT);
            assertDialThrows(UnknownHostException.class, node, "no-such-peer.tail-scale.ts.net", 22, WAIT);
            node.connect("klethron-peer", 7070, WAIT).close();
            assertDialThrows(ConnectException.class, node, peer, 7072, WAIT);
            assertEquals("ok", Probe.call("dialDeadline", StateStoreTest.handle(node)));
            assertDialThrows(SocketTimeoutException.class, node, peer, 7070, Duration.ofSeconds(2));
        }
    }

    @CanIgnoreReturnValue
    static <T extends Throwable> T assertDialThrows(Class<T> expected, TailnetNode node, String host, int port, Duration timeout) {
        return assertThrows(expected, () -> node.connect(host, port, timeout).close());
    }

    private static void assertReset(SocketChannel connection) {
        assertEquals("Connection reset by peer",
                assertThrows(IOException.class, () -> connection.read(ByteBuffer.allocate(1))).getMessage());
    }

    private TailnetNode startNode() throws IOException {
        return TailnetNode.start(LoginTest.CONTEXT, new MemoryStore(), "klethron-dialer", controlUrl);
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

    private static byte[] pattern(long offset, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((offset + i) % 251);
        }
        return bytes;
    }

    private static boolean ready(Selector selector, int timeoutMillis) throws IOException {
        boolean ready = selector.select(timeoutMillis) == 1;
        selector.selectedKeys().clear();
        return ready;
    }
}
