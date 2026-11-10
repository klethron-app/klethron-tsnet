package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.Test;

public final class TailnetNodeTest {
    @Test
    public void reportsLinkedTailscaleVersion() {
        assertTrue(TailnetNode.tailscaleVersion().startsWith("v1."));
        assertTrue(TailnetNode.tailscaleVersion().endsWith("-5b6c39b94d50"));
    }

    @Test
    public void statusBelongsToItsNode() throws Exception {
        Path directory = LoginTest.temporaryDirectory("status-");
        String control = "http://127.0.0.1:1"; // Unreachable: the nodes stay local.
        try (TailnetNode first = TailnetNode.start(LoginTest.CONTEXT, directory.resolve("first").toFile(), "first", control);
                TailnetNode second = TailnetNode.start(LoginTest.CONTEXT, directory.resolve("second").toFile(), "second", control)) {
            NodeStatus status = first.awaitStatus(null, Duration.ZERO);
            assertThrows(IllegalArgumentException.class, () -> second.awaitStatus(status, Duration.ZERO));
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }

    @Test
    public void concurrentCloseWaitsForShutdown() {
        assertEquals("ok", Probe.call("concurrentClose"));
    }
}
