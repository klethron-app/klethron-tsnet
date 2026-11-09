package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import org.junit.Test;

public final class TailnetNodeTest {
    @Test
    public void reportsLinkedTailscaleVersion() {
        assertTrue(TailnetNode.tailscaleVersion().startsWith("v1."));
        assertTrue(TailnetNode.tailscaleVersion().endsWith("-6372b9a43a5c"));
    }

    @Test
    public void closesNativeNodesRepeatedly() {
        for (int i = 0; i < 100; i++) {
            try (TailnetNode node = new TailnetNode()) {
                node.close();
            }
        }
    }

    @Test
    public void statusBelongsToItsNode() {
        try (TailnetNode first = new TailnetNode(); TailnetNode second = new TailnetNode()) {
            NodeStatus status = first.awaitStatus(null, Duration.ZERO);
            assertEquals(NodeStatus.State.NO_STATE, first.awaitStatus(status, Duration.ZERO).state());
            assertThrows(IllegalArgumentException.class, () -> second.awaitStatus(status, Duration.ZERO));
        }
    }

    @Test
    public void concurrentCloseWaitsForShutdown() {
        assertTrue(TailnetNode.tailscaleVersion().startsWith("v1.")); // Load the library.
        assertTrue(nativeConcurrentClose());
    }

    private static native boolean nativeConcurrentClose();
}
