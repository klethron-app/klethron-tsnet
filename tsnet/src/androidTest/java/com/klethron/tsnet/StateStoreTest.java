package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.klethron.tsnet.demo.store.SqliteStore;
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

    @Test(timeout = 30000)
    public void preservesOpaqueKeysValuesAndDeletes() throws Exception {
        try (TailnetNode node = start(new MemoryStore())) {
            assertEquals("ok", probe(node));
        }
    }

    @Test(timeout = 60000)
    public void callbackFailuresAbortAndReleaseTheStore() throws Exception {
        for (boolean failRead : new boolean[] {true, false}) {
            AtomicBoolean failing = new AtomicBoolean(true);
            AtomicInteger calls = new AtomicInteger();
            MemoryStore memory = new MemoryStore();
            StateStore store = new StateStore() {
                @Override
                public byte @Nullable [] read(String key) throws IOException {
                    calls.incrementAndGet();
                    if (failRead && failing.get()) throw new IOException("private callback detail");
                    return memory.read(key);
                }

                @Override
                public void write(String key, byte @Nullable [] value) throws IOException {
                    calls.incrementAndGet();
                    if (!failRead && failing.get()) throw new IOException("private callback detail");
                    memory.write(key, value);
                }
            };
            assertEquals("State store failed; close the node before retrying",
                    assertThrows(IOException.class, () -> start(store)).getMessage());
            failing.set(false);
            try (TailnetNode node = start(store)) {
                LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
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
        MemoryStore memory = new MemoryStore();
        StateStore store = new StateStore() {
            @Override
            public byte @Nullable [] read(String key) { return memory.read(key); }

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
                memory.write(key, value);
            }
        };
        try (TailnetNode node = start(store)) {
            current.set(node);
            long handle = handle(node);
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
    public void callbacksRejectNodeOperationsWithoutChangingState() throws Exception {
        AtomicReference<TailnetNode> current = new AtomicReference<>();
        AtomicInteger checked = new AtomicInteger();
        MemoryStore memory = new MemoryStore();
        StateStore store = new StateStore() {
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
            public byte @Nullable [] read(String key) {
                check(key);
                return memory.read(key);
            }

            @Override
            public void write(String key, byte @Nullable [] value) {
                check(key);
                memory.write(key, value);
            }
        };
        try (TailnetNode node = start(store)) {
            current.set(node);
            LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
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
        MemoryStore memory = new MemoryStore();
        StateStore store = new StateStore() {
            @Override
            public byte @Nullable [] read(String key) { return memory.read(key); }

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
                memory.write(key, value);
            }
        };
        try (TailnetNode node = start(store)) {
            LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
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
            LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
        }
    }

    @Test(timeout = 30000)
    public void callerCanHandlePersistenceFailureAndKeepRunning() throws Exception {
        var directory = LoginTest.temporaryDirectory("fallback-");
        try (SqliteStore disk = new SqliteStore(directory.resolve("state.db").toFile())) {
            disk.close(); // SQLite writes now fail; the embedder chooses to keep the state in memory.
            MemoryStore memory = new MemoryStore();
            AtomicInteger failures = new AtomicInteger();
            StateStore fallback = new StateStore() {
                @Override
                public byte @Nullable [] read(String key) { return memory.read(key); }

                @Override
                public void write(String key, byte @Nullable [] value) {
                    memory.write(key, value);
                    try {
                        disk.write(key, value);
                    } catch (RuntimeException handled) {
                        failures.incrementAndGet();
                    }
                }
            };
            for (int i = 0; i < 2; i++) {
                try (TailnetNode node = start(fallback)) {
                    LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
                    assertEquals("ok", probe(node));
                }
            }
            assertTrue(failures.get() > 0);
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }

    private TailnetNode start(StateStore store) throws IOException {
        return TailnetNode.start(LoginTest.CONTEXT, store, "store-test", control);
    }

    static long handle(TailnetNode node) throws Exception {
        var field = TailnetNode.class.getDeclaredField("handle");
        field.setAccessible(true);
        return field.getLong(node);
    }

    private static String probe(TailnetNode node) throws Exception { return Probe.call("store", handle(node)); }
}
