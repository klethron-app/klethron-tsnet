package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
                }
                memory.write(key, value);
            }
        };
        try (TailnetNode node = start(store)) {
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
            } finally {
                release.countDown();
            }
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

    private static long handle(TailnetNode node) throws Exception {
        var field = TailnetNode.class.getDeclaredField("handle");
        field.setAccessible(true);
        return field.getLong(node);
    }

    private static String probe(TailnetNode node) throws Exception { return Probe.call("store", handle(node)); }
}
