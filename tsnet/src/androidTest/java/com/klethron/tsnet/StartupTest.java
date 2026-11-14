package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static com.klethron.tsnet.TestSupport.WAIT;
import static com.klethron.tsnet.TestSupport.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import org.junit.After;
import org.junit.Test;

/// Starts against local control with a relay hint and requires complete metadata at the first RUNNING.
public final class StartupTest {
    private static final String PEER = "klethron-peer.tail-scale.ts.net";

    @After
    public void closeControl() {
        Probe.call("stopControl");
    }

    @Test(timeout = 30000)
    public void validatesHintsAndPublishesHomeChanges() {
        assertEquals("ok", Probe.call("relayHint", "validation"));
    }

    @Test(timeout = 30000)
    public void freshControlConnectsTheHomeRelayBeforeNetcheckFinishes() {
        assertEquals("ok", Probe.call("freshRelayHint"));
    }

    @Test(timeout = 60000)
    public void doesNotOverwriteAConcurrentHomeSelection() {
        for (int i = 0; i < 32; i++) {
            assertEquals("ok", Probe.call("relayHint", "race"));
        }
    }

    @Test(timeout = 90000)
    public void firstRunningHasPeerMetadataAcrossRestarts() throws Exception {
        String control = startControl();
        MemoryStore store = new MemoryStore();
        for (int iteration = 0; iteration < 6; iteration++) {
            try (TailnetNode node = TailnetNode.start(CONTEXT, store, "klethron-startup", control)) {
                assertReadyAndEcho(node);
            }
        }
    }

    @Test(timeout = 60000)
    public void initialRunningSnapshotDoesNotWaitForTheMetadataWorker() {
        assertEquals("ok", Probe.call("readyStatus", startControl()));
    }

    @Test(timeout = 60000)
    public void ignoresARelayMissingFromTheFreshMap() throws Exception {
        assertHintFallback("missing");
    }

    @Test(timeout = 60000)
    public void fallsBackFromAnUnreachableRelay() throws Exception {
        assertHintFallback("unreachable");
    }

    private static void assertHintFallback(String mode) throws Exception {
        String control = startControl();
        assertEquals("ok", Probe.call("setRelayHint", mode));
        try (TailnetNode node = TailnetNode.start(CONTEXT, new MemoryStore(), "klethron-startup", control)) {
            assertReadyAndEcho(node);
            assertEquals("ok", Probe.call("relayHome", Probe.handle(node)));
        }
    }

    private static String startControl() {
        String control = Probe.call("startControl", false);
        assertTrue(control.startsWith("http://127.0.0.1:"));
        assertTrue(Probe.call("startPeer").startsWith("100."));
        return control;
    }

    private static void assertReadyAndEcho(TailnetNode node) throws Exception {
        NodeStatus status = await(node, current -> current.state() == NodeStatus.State.RUNNING);
        assertTrue("The first RUNNING snapshot must contain the fresh peer map",
                status.peers().stream().anyMatch(peer -> PEER.equals(peer.dnsName())));
        assertEquals("klethron-startup.tail-scale.ts.net", status.dnsName());
        try (SocketChannel channel = node.connect(PEER, 7070, WAIT)) {
            channel.socket().setSoTimeout(30000);
            assertEquals(1, channel.write(ByteBuffer.wrap(new byte[] {17})));
            assertEquals(17, channel.socket().getInputStream().read());
        }
    }
}
