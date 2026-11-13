package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static com.klethron.tsnet.TestSupport.WAIT;
import static com.klethron.tsnet.TestSupport.assertDialThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
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

    private static void echo(TailnetNode node, String host) throws Exception {
        try (SocketChannel connection = node.connect(host, 7070, WAIT)) {
            assertEquals(1, connection.write(ByteBuffer.wrap(new byte[] {42})));
            ByteBuffer received = ByteBuffer.allocate(1);
            assertEquals(1, connection.read(received));
            assertEquals(42, received.get(0));
        }
    }
}
