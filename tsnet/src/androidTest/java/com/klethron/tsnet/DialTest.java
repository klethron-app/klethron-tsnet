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
import android.system.OsConstants;
import android.system.StructPollfd;
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
        try (TailnetNode node = startNode(); ParcelFileDescriptor connection = node.dial(PEER_NAME, 7070, WAIT)) {
            NodeStatus status = LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING && !s.peers().isEmpty());
            assertEquals(1, status.peers().size());
            NodeStatus.Peer listed = status.peers().get(0);
            assertEquals(PEER_NAME, listed.dnsName());
            assertEquals("android", listed.os());
            assertFalse(listed.online());
            assertEquals(InetAddresses.parseNumericAddress(peer), listed.addresses().get(0));
            assertEquals(2, listed.addresses().size());
            assertEquals(List.of(), listed.sshHostKeys());
            assertTrue(status.toString(), status.toString().endsWith(", peers=1}"));
            assertFalse(status.toString().contains("klethron-peer"));
            write(connection, new byte[] {1});
            assertEquals(1, new FileInputStream(connection.getFileDescriptor()).read());

            // Hides the sharee and Mullvad nodes; sorts by name, lists IPv4 first, trims host keys and
            // drops one with a comma.
            Probe.call("addPeers");
            NodeStatus.Peer other = new NodeStatus.Peer("klethron-other.tail-scale.ts.net", "linux", false, List.of(
                    InetAddresses.parseNumericAddress("100.101.0.1"), InetAddresses.parseNumericAddress("fd7a:115c:a1e0::101:1")),
                    List.of("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"));
            assertEquals(List.of(other, listed), LoginTest.await(node, s -> s.peers().size() > 1).peers());
            assertEquals("true", Probe.call("setPeerOnline", true));
            NodeStatus.Peer online = new NodeStatus.Peer(PEER_NAME, "android", true, listed.addresses(), List.of());
            assertEquals(List.of(other, online), LoginTest.await(node, s -> !s.peers().contains(listed)).peers());
            // The other peer has no endpoints: connecting, not waiting to run, times out.
            assertThrows(SocketTimeoutException.class, () -> node.dial("100.101.0.1", 22, Duration.ofSeconds(1)));

            node.logout(WAIT);
            // The peers clear after the state changes.
            LoginTest.await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN && s.peers().isEmpty());
            // The peer is gone from this node's tailnet: nothing is echoed, and new dials wait for a login.
            write(connection, new byte[] {2});
            assertFalse(poll(connection.getFileDescriptor(), OsConstants.POLLIN, 2000));
            assertThrows(SocketTimeoutException.class, () -> node.dial(peer, 7070, Duration.ofMillis(500)));
        }
    }

    @Test(timeout = 60000)
    public void deliversPeerEndOfStreamAndClosesWithDescriptor() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7071, WAIT)) {
            // The peer greets and closes its side; this side remains writable.
            assertEquals("klethron\n", new String(new FileInputStream(connection.getFileDescriptor()).readAllBytes(),
                    StandardCharsets.US_ASCII));
            write(connection, "after".getBytes(StandardCharsets.US_ASCII));
            connection.close();
            // Closing the descriptor ends the peer's connection after delivering what was written.
            assertEquals("5", Probe.call("awaitReceived", 10000));
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
            for (int i = 0; i < 200; i++) {
                assertEquals(1, Os.write(fd, new byte[] {(byte) i}, 0, 1));
                awaitReady(fd, OsConstants.POLLIN);
                assertEquals(1, Os.read(fd, one, 0, 1));
                assertEquals((byte) i, one[0]);
            }
        }
    }

    @Test(timeout = 120000)
    public void slowsAPeerWhileTheAppDoesNotRead() throws Exception {
        try (TailnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7073, WAIT)) {
            long flooded = awaitFloodStalled();
            assertTrue("Peer was not slowed: " + flooded, flooded < FLOOD);
            var in = new FileInputStream(connection.getFileDescriptor());
            byte[] buffer = new byte[64 << 10];
            byte[] expected = new byte[buffer.length];
            long received = 0;
            for (int read; (read = in.read(buffer)) > 0; received += read) {
                assertTrue("Unexpected bytes after " + received,
                        Arrays.equals(buffer, 0, read, pattern(received, expected), 0, read));
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
            assertTrue("App was not slowed: " + written, written < FLOOD);
            Probe.call("release");
            while (written < FLOOD) {
                awaitReady(fd, OsConstants.POLLOUT);
                written += Os.write(fd, pattern(written, chunk), 0, (int) Math.min(chunk.length, FLOOD - written));
            }
            connection.close();
            assertEquals(FLOOD, Long.parseLong(Probe.call("awaitReceived", 30000)));
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

    private static void write(ParcelFileDescriptor connection, byte[] bytes) {
        try {
            new FileOutputStream(connection.getFileDescriptor()).write(bytes);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
