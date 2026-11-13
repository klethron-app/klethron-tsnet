package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static com.klethron.tsnet.TestSupport.assertDialThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class TailnetNodeTest {
    @Test
    public void nodesKeepTheirStatusAndStore() throws Exception {
        String version = TailnetNode.tailscaleVersion();
        assertTrue(version, version.startsWith("v1.") && version.endsWith("-59064619a0f4"));
        StateStore store = new MemoryStore();
        String control = "http://127.0.0.1:1";
        try (TailnetNode first = TailnetNode.start(CONTEXT, store, "first", control);
                TailnetNode second = TailnetNode.start(CONTEXT, new MemoryStore(), "second", control)) {
            NodeStatus status = first.awaitStatus(null, Duration.ZERO);
            assertThrows(IllegalArgumentException.class, () -> second.awaitStatus(status, Duration.ZERO));
            assertThrows(IllegalStateException.class, () -> TailnetNode.start(CONTEXT, store, "third", control));
        }
        TailnetNode.start(CONTEXT, store, "reopened", control).close();
    }

    @Test(timeout = 30000)
    @SuppressWarnings({"AssertThrowsMultipleStatements", "NullAway"}) // Check blocking loops and null arguments.
    public void refusesInvalidCallsAndCloseEndsBlockedOnes() throws Exception {
        StateStore store = new MemoryStore();
        try (TailnetNode node = TailnetNode.start(CONTEXT, store, "close", "http://127.0.0.1:1")) {
            for (String host : new String[] {"", "a".repeat(256), "a\0b"}) {
                assertDialThrows(IllegalArgumentException.class, node, host, 22, Duration.ZERO);
            }
            assertDialThrows(IllegalArgumentException.class, node, "100.64.0.1", 0, Duration.ZERO);
            assertDialThrows(IllegalArgumentException.class, node, "100.64.0.1", 65536, Duration.ZERO);
            assertDialThrows(NullPointerException.class, node, "100.64.0.1", 22, null);
            // The node never runs.
            assertDialThrows(SocketTimeoutException.class, node, "100.64.0.1", 22, Duration.ofSeconds(-1));
            // Longer than a long of nanoseconds: the waits saturate instead of overflowing.
            Duration forever = Duration.ofSeconds(Long.MAX_VALUE);
            NodeStatus first = node.awaitStatus(null, Duration.ZERO);
            List<CompletableFuture<Throwable>> blocked = List.of(
                    CompletableFuture.supplyAsync(() -> assertThrows(ClosedChannelException.class, () -> {
                        for (NodeStatus latest = first; ; ) latest = node.awaitStatus(latest, forever);
                    })),
                    CompletableFuture.supplyAsync(() ->
                            assertDialThrows(ClosedChannelException.class, node, "100.64.0.1", 22, forever)));
            Thread.sleep(200); // Best effort: let both calls block.
            assertFalse(blocked.stream().anyMatch(CompletableFuture::isDone));
            CompletableFuture<Void> closing = CompletableFuture.runAsync(node::close);
            node.close();
            closing.get(10, TimeUnit.SECONDS);
            for (var call : blocked) call.get(10, TimeUnit.SECONDS);
            node.close();
            assertThrows(ClosedChannelException.class, () -> node.awaitStatus(null, Duration.ZERO));
            assertDialThrows(ClosedChannelException.class, node, "100.64.0.1", 22, Duration.ZERO);
            assertThrows(ClosedChannelException.class, () -> node.logout(Duration.ZERO));
            assertThrows(ClosedChannelException.class, node::login);
            TailnetNode.start(CONTEXT, store, "reopened", "http://127.0.0.1:1").close();
        }
    }
}
