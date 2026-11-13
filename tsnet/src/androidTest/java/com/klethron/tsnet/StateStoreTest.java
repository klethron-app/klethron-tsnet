package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static com.klethron.tsnet.TestSupport.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class StateStoreTest {
    private String control;

    @Before
    public void startControl() { control = Probe.call("startControl", false); }

    @After
    public void stopControl() { Probe.call("stopControl"); }

    @Test(timeout = 60000)
    public void callbackFailuresAbortAndReleaseTheStore() throws Exception {
        for (boolean failRead : new boolean[] {true, false}) {
            AtomicBoolean failing = new AtomicBoolean(true);
            AtomicInteger calls = new AtomicInteger();
            StateStore store = new MemoryStore() {
                @Override
                public byte @Nullable [] read(String key) throws IOException {
                    calls.incrementAndGet();
                    if (failRead && failing.get()) throw new IOException("private callback detail");
                    return super.read(key);
                }

                @Override
                public void write(String key, byte @Nullable [] value) throws IOException {
                    calls.incrementAndGet();
                    if (!failRead && failing.get()) throw new IOException("private callback detail");
                    super.write(key, value);
                }
            };
            assertEquals("State store failed; close the node before retrying",
                    assertThrows(IOException.class, () -> start(store)).getMessage());
            failing.set(false);
            try (TailnetNode node = start(store)) {
                await(node, s -> s.state() == NodeStatus.State.RUNNING);
                failing.set(true);
                assertFalse(probe(node).equals("ok"));
                assertEquals("State store failed; close the node before retrying",
                        assertThrows(IOException.class, () -> node.awaitStatus(null, Duration.ZERO)).getMessage());
                assertThrows(IOException.class, node::login);
                node.close();
                int ended = calls.get();
                assertThrows(IOException.class, () -> node.awaitStatus(null, Duration.ZERO));
                assertEquals(ended, calls.get());
            }
            failing.set(false);
            start(store).close();
        }
    }

    @Test(timeout = 30000)
    public void closeWaitsForAnActiveCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<TailnetNode> current = new AtomicReference<>();
        AtomicBoolean rejected = new AtomicBoolean();
        StateStore store = new MemoryStore() {
            @Override
            public void write(String key, byte @Nullable [] value) throws IOException {
                if (key.startsWith("probe-")) {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Callback timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                    // Another close holds the node lock while waiting for this callback.
                    assertThrows(IllegalStateException.class, Objects.requireNonNull(current.get())::close);
                    rejected.set(true);
                }
                super.write(key, value);
            }
        };
        try (TailnetNode node = start(store)) {
            current.set(node);
            long handle = Probe.handle(node);
            CompletableFuture<String> writing = CompletableFuture.supplyAsync(() -> Probe.call("store", handle));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                CompletableFuture<Void> closing = CompletableFuture.runAsync(node::close);
                Thread.sleep(100);
                assertFalse(closing.isDone());
                release.countDown();
                closing.get(10, TimeUnit.SECONDS);
                writing.get(10, TimeUnit.SECONDS);
                assertTrue(rejected.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 30000)
    public void opaqueValuesSurviveRejectedReentrantCalls() throws Exception {
        AtomicReference<TailnetNode> current = new AtomicReference<>();
        AtomicInteger checked = new AtomicInteger();
        StateStore store = new MemoryStore() {
            private void check(String key) {
                if (!key.startsWith("probe-")) return;
                TailnetNode node = Objects.requireNonNull(current.get());
                MemoryStore other = new MemoryStore();
                assertThrows(IllegalStateException.class, () -> start(other));
                assertThrows(IllegalStateException.class, () -> node.awaitStatus(null, Duration.ZERO));
                assertThrows(IllegalStateException.class, () -> node.connect("100.64.0.1", 80, Duration.ZERO));
                assertThrows(IllegalStateException.class, () -> node.logout(Duration.ZERO));
                assertThrows(IllegalStateException.class, node::login);
                assertThrows(IllegalStateException.class, node::close);
                checked.incrementAndGet();
            }

            @Override
            public byte @Nullable [] read(String key) throws IOException {
                check(key);
                return super.read(key);
            }

            @Override
            public void write(String key, byte @Nullable [] value) throws IOException {
                check(key);
                super.write(key, value);
            }
        };
        try (TailnetNode node = start(store)) {
            current.set(node);
            await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertEquals("ok", probe(node));
            assertEquals(6, checked.get());
            assertEquals(NodeStatus.State.RUNNING, node.awaitStatus(null, Duration.ZERO).state());
        }
        // The failed re-entrant close must not mark the node closed and leave its store reserved.
        start(store).close();
    }

    @Test(timeout = 30000)
    public void logoutWaitsForBackendWritesAfterItsDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean();
        StateStore store = new MemoryStore() {
            @Override
            public void write(String key, byte @Nullable [] value) throws IOException {
                if (pause.compareAndSet(true, false)) {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Callback timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                }
                super.write(key, value);
            }
        };
        try (TailnetNode node = start(store)) {
            await(node, s -> s.state() == NodeStatus.State.RUNNING);
            pause.set(true);
            CompletableFuture<IOException> logout = CompletableFuture.supplyAsync(() ->
                    assertThrows(IOException.class, () -> node.logout(Duration.ofMillis(100))));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Thread.sleep(250);
                assertFalse("Logout returned while its backend could still mutate state", logout.isDone());
            } finally {
                release.countDown();
            }
            assertEquals(SocketTimeoutException.class, logout.get(10, TimeUnit.SECONDS).getClass());
            node.login();
            await(node, s -> s.state() == NodeStatus.State.RUNNING);
        }
    }

    private TailnetNode start(StateStore store) throws IOException {
        return TailnetNode.start(CONTEXT, store, "store-test", control);
    }

    private static String probe(TailnetNode node) throws Exception { return Probe.call("store", Probe.handle(node)); }
}
