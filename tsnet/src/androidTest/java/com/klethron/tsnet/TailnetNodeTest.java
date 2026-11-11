package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class TailnetNodeTest {
    @Test
    public void reportsLinkedTailscaleVersion() {
        assertTrue(TailnetNode.tailscaleVersion().startsWith("v1."));
        assertTrue(TailnetNode.tailscaleVersion().endsWith("-5b6c39b94d50"));
    }

    @Test
    public void nodesKeepTheirStatusAndDirectory() throws Exception {
        Path directory = LoginTest.temporaryDirectory("status-");
        String control = "http://127.0.0.1:1"; // Unreachable: the nodes stay local.
        try (TailnetNode first = TailnetNode.start(LoginTest.CONTEXT, directory.resolve("first").toFile(), "first", control);
                TailnetNode second = TailnetNode.start(LoginTest.CONTEXT, directory.resolve("second").toFile(), "second", control)) {
            NodeStatus status = first.awaitStatus(null, Duration.ZERO);
            assertThrows(IllegalArgumentException.class, () -> second.awaitStatus(status, Duration.ZERO));
            assertThrows(IllegalStateException.class, () ->
                    TailnetNode.start(LoginTest.CONTEXT, directory.resolve("second/../first").toFile(), "third", control));
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }

    @Test(timeout = 30000)
    public void refusesInvalidCallsAndCloseEndsBlockedOnes() throws Exception {
        Path directory = LoginTest.temporaryDirectory("close-");
        try (TailnetNode node = TailnetNode.start(LoginTest.CONTEXT, directory.toFile(), "close", "http://127.0.0.1:1")) {
            for (String host : new String[] {"", "a".repeat(256), "a\0b"}) {
                assertThrows(IllegalArgumentException.class, () -> node.dial(host, 22, Duration.ZERO));
            }
            assertThrows(IllegalArgumentException.class, () -> node.dial("100.64.0.1", 0, Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> node.dial("100.64.0.1", 65536, Duration.ZERO));
            // The node never runs.
            assertThrows(SocketTimeoutException.class, () -> node.dial("100.64.0.1", 22, Duration.ofMillis(200)));
            assertThrows(SocketTimeoutException.class, () -> node.dial("100.64.0.1", 22, Duration.ofSeconds(-1)));
            // Longer than a long of nanoseconds: the waits saturate instead of overflowing.
            Duration forever = Duration.ofSeconds(Long.MAX_VALUE);
            NodeStatus first = node.awaitStatus(null, Duration.ZERO);
            List<CompletableFuture<Throwable>> blocked = List.of(
                    CompletableFuture.supplyAsync(() -> assertThrows(ClosedChannelException.class, () -> {
                        for (NodeStatus latest = first; ; ) latest = node.awaitStatus(latest, forever);
                    })),
                    CompletableFuture.supplyAsync(() -> assertThrows(ClosedChannelException.class,
                            () -> node.dial("100.64.0.1", 22, forever))));
            Thread.sleep(200); // Best effort: let both calls block.
            assertFalse(blocked.stream().anyMatch(CompletableFuture::isDone));
            node.close();
            for (var call : blocked) call.get(10, TimeUnit.SECONDS);
            node.close();
            assertThrows(ClosedChannelException.class, () -> node.awaitStatus(null, Duration.ZERO));
            assertThrows(ClosedChannelException.class, () -> node.dial("100.64.0.1", 22, Duration.ZERO));
            assertThrows(ClosedChannelException.class, () -> node.logout(Duration.ZERO));
            assertThrows(ClosedChannelException.class, node::login);
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }

    @Test
    public void concurrentCloseWaitsForShutdown() {
        assertEquals("ok", Probe.call("concurrentClose"));
    }
}
