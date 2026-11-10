package com.klethron.tsnet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.net.InetAddresses;
import android.os.HandlerThread;
import android.os.MessageQueue;
import android.os.MessageQueue.OnFileDescriptorEventListener;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
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
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String PEER_NAME = "klethron-peer.tail-scale.ts.net";

    private String controlUrl;
    private Path directory;
    private String peer;

    @Before
    public void startPeer() throws Exception {
        controlUrl = LoginTest.nativeProbeStart(false);
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        directory = LoginTest.temporaryDirectory("dial-");
        peer = LoginTest.nativeProbeStartPeer(directory.resolve("peer").toString().getBytes(StandardCharsets.UTF_8));
        assertTrue("Peer failed to start", peer.startsWith("100."));
    }

    @After
    public void stopPeer() throws Exception {
        LoginTest.nativeProbeStop();
        LoginTest.deleteDirectory(directory);
    }

    @Test(timeout = 90000)
    public void echoesThroughAddressAndMagicDnsName() throws Exception {
        try (TailnetNode node = startNode()) {
            // The first dial waits for the node to join.
            for (String host : new String[] {peer, PEER_NAME}) {
                try (ParcelFileDescriptor connection = node.dial(host, 7070, WAIT)) {
                    byte[] sent = new byte[1 << 20];
                    new Random().nextBytes(sent);
                    CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> write(connection, sent));
                    byte[] received = new FileInputStream(connection.getFileDescriptor()).readNBytes(sent.length);
                    writer.get(30, TimeUnit.SECONDS);
                    assertArrayEquals(sent, received);
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void listsPeersAndTheirChanges() throws Exception {
        try (TailnetNode node = startNode()) {
            NodeStatus status = LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING && !s.peers().isEmpty());
            assertEquals(1, status.peers().size());
            NodeStatus.Peer listed = status.peers().get(0);
            assertEquals(PEER_NAME, listed.dnsName());
            assertEquals("android", listed.os());
            assertFalse(listed.online());
            assertEquals(InetAddresses.parseNumericAddress(peer), listed.addresses().get(0));
            assertEquals(2, listed.addresses().size());
            assertTrue(status.toString(), status.toString().endsWith(", peers=1}"));
            assertFalse(status.toString().contains("klethron-peer"));
            try (ParcelFileDescriptor connection = node.dial(listed.dnsName(), 7070, WAIT)) {
                write(connection, "x".getBytes(StandardCharsets.US_ASCII));
                assertEquals('x', new FileInputStream(connection.getFileDescriptor()).read());
            }

            assertTrue(LoginTest.nativeProbeSetPeerOnline(true));
            NodeStatus.Peer online = new NodeStatus.Peer(PEER_NAME, "android", true, listed.addresses());
            assertEquals(List.of(online), LoginTest.await(node, s -> !s.peers().equals(List.of(listed))).peers());

            // Hides the sharee and Mullvad nodes; sorts by name and lists IPv4 first.
            LoginTest.nativeProbeAddPeers();
            NodeStatus.Peer other = new NodeStatus.Peer("klethron-other.tail-scale.ts.net", "linux", false, List.of(
                    InetAddresses.parseNumericAddress("100.101.0.1"), InetAddresses.parseNumericAddress("fd7a:115c:a1e0::101:1")));
            assertEquals(List.of(other, online), LoginTest.await(node, s -> s.peers().size() > 1).peers());

            node.logout(WAIT);
            assertEquals(List.of(), LoginTest.await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN).peers());
        }
    }

    @Test(timeout = 60000)
    public void deliversPeerEndOfStreamAndClosesWithDescriptor() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7071, WAIT)) {
            // The peer greets and closes its side; this side remains writable.
            assertEquals("klethron\n", readToEnd(connection));
            write(connection, "after".getBytes(StandardCharsets.US_ASCII));
            connection.close();
            // Closing the descriptor ends the peer's connection after delivering what was written.
            assertEquals(5, LoginTest.nativeProbeAwaitReceived(10000));
        }
    }

    @Test(timeout = 60000)
    public void servesANonBlockingConsumer() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7070, WAIT)) {
            FileDescriptor fd = nonBlocking(connection);
            byte[] one = new byte[1];
            ErrnoException empty = assertThrows(ErrnoException.class, () -> Os.read(fd, one, 0, 1));
            assertEquals(OsConstants.EAGAIN, empty.errno);
            // Interactive round trips of one byte each.
            long[] nanos = new long[200];
            for (int i = 0; i < nanos.length; i++) {
                long start = System.nanoTime();
                assertEquals(1, Os.write(fd, new byte[] {(byte) i}, 0, 1));
                awaitReady(fd, OsConstants.POLLIN);
                assertEquals(1, Os.read(fd, one, 0, 1));
                assertEquals((byte) i, one[0]);
                nanos[i] = System.nanoTime() - start;
            }
            Arrays.sort(nanos);
            Log.i("DialTest", "Loopback round trip µs: median " + nanos[nanos.length / 2] / 1000
                    + ", max " + nanos[nanos.length - 1] / 1000);
            // A Looper can wait for the descriptor too.
            HandlerThread thread = new HandlerThread("dial-events");
            thread.start();
            try {
                CompletableFuture<Integer> events = new CompletableFuture<>();
                MessageQueue queue = thread.getLooper().getQueue();
                queue.addOnFileDescriptorEventListener(fd, OnFileDescriptorEventListener.EVENT_INPUT, (descriptor, event) -> {
                    events.complete(event);
                    return 0; // Unregisters.
                });
                assertEquals(1, Os.write(fd, one, 0, 1));
                assertEquals(OnFileDescriptorEventListener.EVENT_INPUT, (int) events.get(10, TimeUnit.SECONDS));
                assertEquals(1, Os.read(fd, one, 0, 1));
            } finally {
                thread.quitSafely();
            }
        }
    }

    @Test(timeout = 120000)
    public void slowsAPeerWhileTheAppDoesNotRead() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7073, WAIT)) {
            long flooded = awaitFloodStalled();
            Log.i("DialTest", "Peer stalled after writing " + flooded + " unread bytes");
            assertTrue("Peer was not slowed: " + flooded, flooded < FLOOD);
            FileDescriptor fd = nonBlocking(connection);
            byte[] buffer = new byte[64 << 10];
            long received = 0;
            for (int read; (read = readWhenReady(fd, buffer)) > 0; received += read) {
                assertPattern(received, buffer, read);
            }
            assertEquals(FLOOD, received);
        }
    }

    @Test(timeout = 120000)
    public void slowsTheAppWhileThePeerDoesNotRead() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7074, WAIT)) {
            FileDescriptor fd = nonBlocking(connection);
            byte[] chunk = new byte[64 << 10];
            long written = 0;
            // Until the descriptor stays unwritable for a second.
            while (written < FLOOD && poll(fd, OsConstants.POLLOUT, 1000)) {
                written += Os.write(fd, pattern(written, chunk), 0, (int) Math.min(chunk.length, FLOOD - written));
            }
            Log.i("DialTest", "App stalled after writing " + written + " unread bytes");
            assertTrue("App was not slowed: " + written, written < FLOOD);
            LoginTest.nativeProbeRelease();
            while (written < FLOOD) {
                awaitReady(fd, OsConstants.POLLOUT);
                written += Os.write(fd, pattern(written, chunk), 0, (int) Math.min(chunk.length, FLOOD - written));
            }
            connection.close();
            assertEquals(FLOOD, LoginTest.nativeProbeAwaitReceived(30000));
        }
    }

    @Test(timeout = 60000)
    public void closesAnUnreadConnectionWithNode() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7073, WAIT)) {
            assertTrue("Peer was not slowed", awaitFloodStalled() < FLOOD);
            node.close();
            // With unread data, closing only the tailnet socket leaves the relay blocked.
            assertTrue("Relay stayed open", poll(connection.getFileDescriptor(), 0, 10000));
            ErrnoException closed = assertThrows(ErrnoException.class,
                    () -> Os.write(connection.getFileDescriptor(), new byte[] {1}, 0, 1));
            assertEquals(OsConstants.EPIPE, closed.errno);
        }
    }

    @Test(timeout = 60000)
    public void reachesOnlyTheTailnet() throws Exception {
        try (TailnetNode node = startNode()) {
            node.dial(peer, 7070, WAIT).close(); // Joined.
            // Upstream would dial both through the device's network: the first is the control server.
            int controlPort = URI.create(controlUrl).getPort();
            assertThrows(NoRouteToHostException.class, () -> node.dial("127.0.0.1", controlPort, WAIT));
            assertThrows(NoRouteToHostException.class, () -> node.dial("100.64.0.99", 22, WAIT));
            assertThrows(UnknownHostException.class, () -> node.dial("no-such-peer.tail-scale.ts.net", 22, WAIT));
            assertThrows(ConnectException.class, () -> node.dial(peer, 7072, WAIT));
        }
    }

    @Test(timeout = 60000)
    public void refusesInvalidCallsAndClosesConnectionsWithNode() throws Exception {
        try (TailnetNode node = new TailnetNode()) {
            assertThrows(IllegalStateException.class, () -> node.dial(peer, 7070, WAIT));
            assertThrows(IllegalArgumentException.class, () -> node.dial("", 7070, WAIT));
            assertThrows(IllegalArgumentException.class, () -> node.dial("a".repeat(256), 7070, WAIT));
            assertThrows(IllegalArgumentException.class, () -> node.dial(peer, 0, WAIT));
            assertThrows(IllegalArgumentException.class, () -> node.dial(peer, 65536, WAIT));
            node.start(directory.resolve("node").toFile(), "klethron-dialer", controlUrl);
            assertThrows(IllegalArgumentException.class, () -> node.dial("a\0b", 7070, WAIT));
            try (ParcelFileDescriptor connection = node.dial(peer, 7070, WAIT)) {
                node.close();
                assertEquals(-1, new FileInputStream(connection.getFileDescriptor()).read());
                // EPIPE, rather than a SIGPIPE that would end the process.
                assertThrows(IOException.class, () -> new FileOutputStream(connection.getFileDescriptor()).write(1));
            }
            assertThrows(IllegalStateException.class, () -> node.dial(peer, 7070, WAIT));
        }
    }

    @Test(timeout = 60000)
    public void logoutEndsTailnetAccess() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7070, WAIT)) {
            write(connection, new byte[] {1});
            assertEquals(1, new FileInputStream(connection.getFileDescriptor()).read());
            node.logout(WAIT);
            // The peer is gone from this node's tailnet: nothing is echoed, and new dials wait for a login.
            write(connection, new byte[] {2});
            assertFalse(poll(connection.getFileDescriptor(), OsConstants.POLLIN, 2000));
            assertThrows(SocketTimeoutException.class, () -> node.dial(peer, 7070, Duration.ofMillis(500)));
        }
    }

    @Test(timeout = 60000)
    public void timesOutAndWakesOnCloseWhileNotRunning() throws Exception {
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.resolve("node").toFile(), "klethron-dialer", "http://127.0.0.1:1");
            assertThrows(SocketTimeoutException.class, () -> node.dial(peer, 7070, Duration.ofMillis(200)));
            CompletableFuture<Throwable> dialer = CompletableFuture.supplyAsync(() ->
                    assertThrows(IllegalStateException.class, () -> node.dial(peer, 7070, Duration.ofMinutes(5))));
            Thread.sleep(200); // Best effort: let the dial block.
            node.close();
            dialer.get(10, TimeUnit.SECONDS);
        }
    }

    private TailnetNode startNode() throws IOException {
        TailnetNode node = new TailnetNode();
        try {
            node.start(directory.resolve("node").toFile(), "klethron-dialer", controlUrl);
        } catch (IOException | RuntimeException e) {
            node.close();
            throw e;
        }
        return node;
    }

    /// Waits until the peer on port 7073 has written and then made no progress for a second.
    private static long awaitFloodStalled() throws InterruptedException {
        long flooded = 0;
        for (long last = 0; flooded == 0 || flooded != last; Thread.sleep(1000)) {
            last = flooded;
            flooded = LoginTest.nativeProbeFlooded();
        }
        return flooded;
    }

    /// Bytes of the probe pattern (byte `i` is `i % 251`) that the peer floods on port 7073 and
    /// that its stalled port 7074 expects. More than both nodes can buffer for one connection.
    private static final long FLOOD = 48 << 20;

    /// Fills `bytes` with the probe pattern from stream offset `offset`.
    private static byte[] pattern(long offset, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((offset + i) % 251);
        }
        return bytes;
    }

    private static void assertPattern(long offset, byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] != (byte) ((offset + i) % 251)) {
                throw new AssertionError("Unexpected byte at offset " + (offset + i));
            }
        }
    }

    private static FileDescriptor nonBlocking(ParcelFileDescriptor connection) throws ErrnoException {
        FileDescriptor fd = connection.getFileDescriptor();
        Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) | OsConstants.O_NONBLOCK);
        return fd;
    }

    /// Returns whether `fd` became ready for `events` within `timeoutMillis`.
    private static boolean poll(FileDescriptor fd, int events, int timeoutMillis) throws ErrnoException {
        StructPollfd poll = new StructPollfd();
        poll.fd = fd;
        poll.events = (short) events;
        return Os.poll(new StructPollfd[] {poll}, timeoutMillis) == 1;
    }

    private static void awaitReady(FileDescriptor fd, int events) throws ErrnoException {
        assertTrue("Not ready within 10 s", poll(fd, events, 10000));
    }

    /// Reads once `fd` is readable, returning 0 at end of stream.
    private static int readWhenReady(FileDescriptor fd, byte[] buffer) throws Exception {
        awaitReady(fd, OsConstants.POLLIN);
        return Os.read(fd, buffer, 0, buffer.length);
    }

    private static void write(ParcelFileDescriptor connection, byte[] bytes) {
        try {
            new FileOutputStream(connection.getFileDescriptor()).write(bytes);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String readToEnd(ParcelFileDescriptor connection) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new FileInputStream(connection.getFileDescriptor()).transferTo(bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
