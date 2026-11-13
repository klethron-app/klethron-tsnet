package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static com.klethron.tsnet.TestSupport.WAIT;
import static com.klethron.tsnet.TestSupport.assertDialThrows;
import static com.klethron.tsnet.TestSupport.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class DnsTest {
    private String controlUrl;

    @Before
    public void startResolver() {
        controlUrl = Probe.call("startControl", false);
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        assertTrue("DNS peer failed to start", Probe.call("startPeer", "dns").startsWith("100."));
    }

    @After
    public void stopResolver() {
        Probe.call("stopControl");
    }

    @Test(timeout = 60000)
    public void reachesASplitDnsResolverThroughTheTailnet() throws Exception {
        try (TailnetNode node = TailnetNode.start(CONTEXT, new MemoryStore(), "dns-test", controlUrl)) {
            echo(node, "udp.split.test");
        }
    }

    @Test(timeout = 90000)
    public void retriesTruncatedAndUnansweredDnsOverTailnetTcp() throws Exception {
        try (TailnetNode node = TailnetNode.start(CONTEXT, new MemoryStore(), "dns-test", controlUrl)) {
            echo(node, "tcp.split.test");
            echo(node, "timeout.split.test");
        }
    }

    @Test(timeout = 60000)
    public void matchesMixedCaseSplitDnsAtLabelBoundaries() throws Exception {
        try (TailnetNode node = TailnetNode.start(CONTEXT, new MemoryStore(), "dns-test", controlUrl)) {
            echo(node, "eChO.mIxEd.TeSt");
            echo(node, "mIxEd.TeSt");
            assertDialThrows(UnknownHostException.class, node, "echo.notmixed.test", 7070, WAIT);
        }
    }

    @Test(timeout = 60000)
    public void triesLaterDnsAnswersWithinTheCallersDeadline() throws Exception {
        try (TailnetNode node = startNode()) {
            // Each first address is an actual network-map peer with no working endpoints.
            for (String host : new String[] {"fallback4.split.test", "fallback6.split.test", "dual.split.test",
                    "same-family.service.test"}) {
                echo(node, host, Duration.ofSeconds(5));
            }
        }
    }

    @Test(timeout = 60000)
    public void filtersEveryDnsAnswerToTheTailnet() throws Exception {
        try (TailnetNode node = startNode()) {
            echo(node, "mixed.split.test");
            assertDialThrows(NoRouteToHostException.class, node, "blocked.split.test", 7070, WAIT);
        }
    }

    @Test(timeout = 60000)
    public void retainsBothServiceAddressFamiliesRegardlessOfRecordOrder() throws Exception {
        try (TailnetNode node = startNode()) {
            for (String host : new String[] {"dual.service.test", "reverse.service.test"}) {
                assertEquals("ok", Probe.call("dialCandidates", Probe.handle(node) + "," + host));
                echo(node, host);
            }
        }
    }

    @Test(timeout = 60000)
    public void resolvesPeerNamesWithMagicDnsDisabled() throws Exception {
        assertEquals("ok", Probe.call("disableMagicDNS"));
        try (TailnetNode node = startNode()) {
            for (String host : new String[] {"klethron-peer", "klethron-peer.tail-scale.ts.net"}) {
                assertEquals("ok", Probe.call("dialCandidates", Probe.handle(node) + "," + host));
                echo(node, host);
            }
        }
    }

    @Test(timeout = 60000)
    public void closesSuccessfulLosingConnections() throws Exception {
        try (TailnetNode node = startNode()) {
            assertEquals("ok", Probe.call("dialRace", Probe.handle(node) + ",loser"));
            echo(node, "dual.service.test", Duration.ofSeconds(5));
            assertEquals("ok", Probe.call("awaitDialRace", "started,1"));
            assertEquals("ok", Probe.call("awaitDialRace", "closed,1"));
        }
    }

    @Test(timeout = 60000)
    public void closesLateConnectionsAfterTheDeadline() throws Exception {
        try (TailnetNode node = startNode()) {
            assertEquals("ok", Probe.call("dialRace", Probe.handle(node) + ",cancel"));
            assertDialThrows(SocketTimeoutException.class, node, "dual.service.test", 7070, Duration.ofSeconds(2));
            assertEquals("ok", Probe.call("awaitDialRace", "started,2"));
            assertEquals("ok", Probe.call("awaitDialRace", "closed,2"));
        }
    }

    @Test(timeout = 90000)
    public void logoutAndCloseCancelBothAddressFamilies() throws Exception {
        for (boolean close : new boolean[] {false, true}) {
            try (TailnetNode node = startNode()) {
                assertEquals("ok", Probe.call("dialRace", Probe.handle(node) + ",cancel"));
                CompletableFuture<IOException> pending = CompletableFuture.supplyAsync(() -> close
                        ? assertDialThrows(ClosedChannelException.class, node, "dual.service.test", 7070, WAIT)
                        : assertDialThrows(ConnectException.class, node, "dual.service.test", 7070, WAIT));
                assertEquals("ok", Probe.call("awaitDialRace", "started,2"));
                if (close) node.close();
                else node.logout(WAIT);
                pending.get(5, TimeUnit.SECONDS);
                assertEquals("ok", Probe.call("awaitDialRace", "closed,2"));
            }
        }
    }

    private TailnetNode startNode() throws IOException {
        TailnetNode node = TailnetNode.start(CONTEXT, new MemoryStore(), "dns-test", controlUrl);
        try {
            await(node, status -> status.state() == NodeStatus.State.RUNNING && !status.peers().isEmpty());
            return node;
        } catch (IOException | RuntimeException | Error failure) {
            node.close();
            throw failure;
        }
    }

    private static void echo(TailnetNode node, String host) throws Exception {
        echo(node, host, WAIT);
    }

    private static void echo(TailnetNode node, String host, Duration timeout) throws Exception {
        try (SocketChannel connection = node.connect(host, 7070, timeout)) {
            assertEquals(1, connection.write(ByteBuffer.wrap(new byte[] {42})));
            ByteBuffer received = ByteBuffer.allocate(1);
            assertEquals(1, connection.read(received));
            assertEquals(42, received.get(0));
        }
    }
}
