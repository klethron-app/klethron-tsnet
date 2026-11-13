package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.klethron.tsnet.demo.store.SqliteStore;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class TailnetLockTest {
    private String control;

    @Before
    public void startControl() { control = Probe.call("startControl", "locked"); }

    @After
    public void stopControl() { Probe.call("stopControl"); }

    @Test(timeout = 60000)
    public void restoresTrustAndFiltersUnsignedPeersAfterRestart() throws Exception {
        var directory = LoginTest.temporaryDirectory("lock-");
        var file = directory.resolve("state.db").toFile();
        AtomicReference<String> authorityKey = new AtomicReference<>();
        try {
            try (SqliteStore disk = new SqliteStore(file);
                    TailnetNode node = start(recordAuthorityKey(disk, authorityKey))) {
                LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
                assertEquals("ok", probe("tailnetLock", node, "init"));
                assertNotNull(authorityKey.get());
                assertNotNull(disk.read(authorityKey.get()));
            }
            Probe.call("tailnetLockRestart");
            try (SqliteStore disk = new SqliteStore(file); TailnetNode node = start(disk)) {
                LoginTest.await(node, s -> {
                    assertNull("Restart requested another login", s.loginUrl());
                    return s.state() == NodeStatus.State.RUNNING;
                });
                assertEquals("ok", probe("tailnetLock", node, "status"));
                node.logout(Duration.ofSeconds(10));
                assertNull("Logout left authority history behind", disk.read(authorityKey.get()));
            }
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }

    @Test(timeout = 60000)
    public void compactsAndReopensSignedHistoryThroughCallbacks() throws Exception {
        var directory = LoginTest.temporaryDirectory("compact-");
        try (SqliteStore disk = new SqliteStore(directory.resolve("state.db").toFile());
                TailnetNode node = start(disk)) {
            assertEquals("ok", probe("tailnetStore", node, "compact"));
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }

    @Test(timeout = 60000)
    public void authorityCallbackFailuresStopTheNode() throws Exception {
        for (boolean failRead : new boolean[] {true, false}) {
            AtomicBoolean failing = new AtomicBoolean();
            MemoryStore memory = new MemoryStore();
            StateStore store = new StateStore() {
                @Override
                public byte @Nullable [] read(String key) throws IOException {
                    if (failRead && failing.get() && key.startsWith("_tailnet-lock/")) throw new IOException("private detail");
                    return memory.read(key);
                }

                @Override
                public void write(String key, byte @Nullable [] value) throws IOException {
                    if (!failRead && failing.get() && key.startsWith("_tailnet-lock/")) throw new IOException("private detail");
                    memory.write(key, value);
                }
            };
            try (TailnetNode node = start(store)) {
                LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
                failing.set(true);
                assertFalse(probe("tailnetStore", node, "seed").equals("ok"));
                assertEquals("State store failed; close the node before retrying",
                        assertThrows(IOException.class, () -> node.awaitStatus(null, Duration.ZERO)).getMessage());
                assertNull(memory.read("_tailnet-lock/probe-authority"));
            }
        }
    }

    @Test(timeout = 60000)
    public void failedCredentialDeletionKeepsPinnedTrust() throws Exception {
        MemoryStore memory = new MemoryStore();
        AtomicReference<String> authorityKey = new AtomicReference<>();
        AtomicBoolean deleting = new AtomicBoolean();
        StateStore store = new StateStore() {
            @Override
            public byte @Nullable [] read(String key) throws IOException { return memory.read(key); }

            @Override
            public void write(String key, byte @Nullable [] value) throws IOException {
                if (deleting.get() && key.startsWith("profile-") && value == null) {
                    throw new IOException("credential deletion failed");
                }
                memory.write(key, value);
            }
        };
        try (TailnetNode node = start(recordAuthorityKey(store, authorityKey))) {
            LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertEquals("ok", probe("tailnetLock", node, "init"));
            assertNotNull(authorityKey.get());
            byte[] pinned = memory.read(authorityKey.get());
            assertNotNull(pinned);
            deleting.set(true);
            assertThrows(IOException.class, () -> node.logout(Duration.ofSeconds(10)));
            node.close();
            assertTrue("Failed deletion discarded pinned trust", java.util.Arrays.equals(pinned, memory.read(authorityKey.get())));
        }
    }

    @Test(timeout = 60000)
    public void corruptSavedAuthorityStopsStartupWithoutReplacingIt() throws Exception {
        MemoryStore memory = new MemoryStore();
        AtomicReference<String> authorityKey = new AtomicReference<>();
        try (TailnetNode node = start(recordAuthorityKey(memory, authorityKey))) {
            LoginTest.await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertEquals("ok", probe("tailnetLock", node, "init"));
        }
        String key = authorityKey.get();
        assertNotNull(key);
        byte[] corrupt = new byte[] {1, 2, 3};
        memory.write(key, corrupt);
        assertEquals("State store failed; close the node before retrying",
                assertThrows(IOException.class, () -> start(memory)).getMessage());
        assertTrue(java.util.Arrays.equals(corrupt, memory.read(key)));
    }

    private TailnetNode start(StateStore store) throws IOException {
        return TailnetNode.start(LoginTest.CONTEXT, store, "lock-test", control);
    }

    private static StateStore recordAuthorityKey(StateStore store, AtomicReference<String> savedKey) {
        return new StateStore() {
            @Override
            public byte @Nullable [] read(String key) throws IOException { return store.read(key); }

            @Override
            public void write(String key, byte @Nullable [] value) throws IOException {
                store.write(key, value);
                if (key.startsWith("_tailnet-lock/") && value != null) savedKey.set(key);
            }
        };
    }

    private static String probe(String name, TailnetNode node, String action) throws Exception {
        var field = TailnetNode.class.getDeclaredField("handle");
        field.setAccessible(true);
        return Probe.call(name, field.getLong(node) + " " + action);
    }
}
