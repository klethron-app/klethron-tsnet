package com.klethron.tsnet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
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
        assertTrue("Nodes must dial off the UI thread", Looper.myLooper() != Looper.getMainLooper());
        controlUrl = LoginTest.nativeProbeStart(false);
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        directory = StartupTest.temporaryDirectory("dial-");
        peer = LoginTest.nativeProbeStartPeer(directory.resolve("peer").toString().getBytes(StandardCharsets.UTF_8));
        assertTrue("Peer failed to start", peer.startsWith("100."));
    }

    @After
    public void stopPeer() throws Exception {
        LoginTest.nativeProbeStop();
        StartupTest.deleteDirectory(directory);
    }

    @Test(timeout = 90000)
    public void echoesThroughAddressAndMagicDnsName() throws Exception {
        try (TsnetNode node = startNode()) {
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

    @Test(timeout = 60000)
    public void deliversPeerEndOfStreamAndClosesWithDescriptor() throws Exception {
        try (TsnetNode node = startNode();
                ParcelFileDescriptor connection = node.dial(peer, 7071, WAIT)) {
            // The peer greets and closes its side; this side remains writable.
            assertEquals("klethron\n", readToEnd(connection));
            write(connection, "after".getBytes(StandardCharsets.US_ASCII));
            connection.close();
            // Closing the descriptor ends the peer's connection after delivering what was written.
            assertEquals(5, LoginTest.nativeProbeAwaitGreeted(10000));
        }
    }

    @Test(timeout = 60000)
    public void reachesOnlyTheTailnet() throws Exception {
        try (TsnetNode node = startNode()) {
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
        TsnetNode node = new TsnetNode();
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
        }
        assertThrows(IllegalStateException.class, () -> node.dial(peer, 7070, WAIT));
    }

    @Test(timeout = 60000)
    public void timesOutAndWakesOnCloseWhileNotRunning() throws Exception {
        TsnetNode node = new TsnetNode();
        node.start(directory.resolve("node").toFile(), "klethron-dialer", "http://127.0.0.1:1");
        assertThrows(SocketTimeoutException.class, () -> node.dial(peer, 7070, Duration.ofMillis(200)));
        CompletableFuture<Throwable> dialer = CompletableFuture.supplyAsync(() ->
                assertThrows(IllegalStateException.class, () -> node.dial(peer, 7070, Duration.ofMinutes(5))));
        Thread.sleep(200); // Let the dial block.
        node.close();
        dialer.get(10, TimeUnit.SECONDS);
    }

    private TsnetNode startNode() throws IOException {
        TsnetNode node = new TsnetNode();
        node.start(directory.resolve("node").toFile(), "klethron-dialer", controlUrl);
        return node;
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
