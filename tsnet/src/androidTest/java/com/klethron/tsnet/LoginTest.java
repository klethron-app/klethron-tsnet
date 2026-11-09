package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Starts nodes through the public API against a loopback test control server that requires
 * interactive login. The test completes login in place of the user's browser.
 */
public final class LoginTest {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private static final Duration WAIT = Duration.ofSeconds(30);

    private String controlUrl;
    private Path directory;

    @Before
    public void startControl() throws Exception {
        controlUrl = nativeProbeStart(true);
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        directory = temporaryDirectory("login-🔒-").resolve("node");
    }

    @After
    public void stopControl() throws Exception {
        nativeProbeStop();
        deleteDirectory(directory.getParent());
    }

    @Test(timeout = 90000)
    public void logsInInteractivelyAndResumesFromState() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-login-test", controlUrl);
            assertThrows(IllegalStateException.class, () -> node.start(directory.toFile(), "again", controlUrl));
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertNull(status.revocation());
            assertNull(status.error());
            assertTrue(status.addresses().isEmpty());
            // Never print login URLs, even in test failures.
            assertTrue("Unexpected login URL", status.loginUrl().startsWith(controlUrl + "/auth/"));
            assertFalse(status.toString().contains("/auth/"));
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));

            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.loginUrl());
            addresses = status.addresses();
            assertTrue(addresses.get(0) instanceof Inet4Address);
            assertEquals(100, Byte.toUnsignedInt(((Inet4Address) addresses.get(0)).getAddress()[0]));
            // Nothing changed: the timeout returns the same status.
            assertEquals(status.version, node.awaitStatus(status, Duration.ofMillis(100)).version);
        }
        assertOnlyStateFile();

        // The persisted identity resumes without another login.
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-login-test", controlUrl);
            NodeStatus status = await(node, s -> {
                assertNull("Resumed node asked to log in again", s.loginUrl());
                return s.state() == NodeStatus.State.RUNNING;
            });
            assertEquals(addresses, status.addresses());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 120000)
    public void logoutRequiresANewLogin() throws Exception {
        String firstUrl;
        try (TailnetNode node = new TailnetNode()) {
            assertThrows(IllegalStateException.class, () -> node.logout(WAIT));
            assertThrows(IllegalStateException.class, node::login);
            node.start(directory.toFile(), "klethron-logout-test", controlUrl);
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            firstUrl = status.loginUrl();
            node.logout(WAIT); // Not logged in: no effect.
            assertEquals(firstUrl, node.awaitStatus(null, WAIT).loginUrl());
            assertTrue(nativeProbeComplete(firstUrl.getBytes(StandardCharsets.UTF_8)));
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());

            assertThrows(SocketTimeoutException.class, () -> node.logout(Duration.ZERO));
            node.logout(WAIT);
            status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertNull(status.loginUrl());
            assertNull("A logout is not a revocation", status.revocation());
            assertTrue(status.addresses().isEmpty());
            node.logout(WAIT); // Already logged out.
            NodeStatus unchanged = node.awaitStatus(status, Duration.ofMillis(500));
            assertEquals(status + " then " + unchanged, status.version, unchanged.version);

            // The same node can log in again, with a new login URL.
            node.login();
            status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertFalse("Login URL was reused", firstUrl.equals(status.loginUrl()));
            // Logout reset the prefs, including control URL and hostname; login restores both.
            assertTrue("Unexpected login URL", status.loginUrl().startsWith(controlUrl + "/auth/"));
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());
            // Control keeps the logged-out registration: testcontrol ignores the logout's expiry.
            assertEquals("klethron-logout-test\nklethron-logout-test", nativeProbeHostnames());
            node.logout(WAIT);
            assertTrue(await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN).addresses().isEmpty());
        }
        assertOnlyStateFile();

        // The logout persists: the directory no longer resumes, and a new login is offered.
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-logout-test", controlUrl);
            NodeStatus status = await(node, s -> {
                assertTrue("Logged-out node resumed", s.state() != NodeStatus.State.RUNNING);
                return s.loginUrl() != null;
            });
            assertFalse("Login URL was reused", firstUrl.equals(status.loginUrl()));
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());
            node.close();
            assertThrows(IllegalStateException.class, () -> node.logout(WAIT));
            assertThrows(IllegalStateException.class, node::login);
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 120000)
    public void failedLogoutKeepsTheLoginForRetries() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-logout-failure-test", controlUrl);
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            addresses = await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses();
            nativeProbeRejectRegistrations(true);
            assertThrows(IOException.class, () -> node.logout(WAIT));
            status = await(node, s -> s.state() == NodeStatus.State.STOPPED);
            assertEquals(addresses, status.addresses());
            // Let netmap updates arrive: upstream saved an empty node key over the login here.
            assertEquals(status.version, node.awaitStatus(status, Duration.ofSeconds(3)).version);
        }
        assertOnlyStateFile();

        // The login persisted: a new node resumes it. Once control accepts, the same node that
        // failed to log out retries successfully.
        nativeProbeRejectRegistrations(false);
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-logout-failure-test", controlUrl);
            NodeStatus status = await(node, s -> {
                assertNull("Node asked to log in again", s.loginUrl());
                return s.state() == NodeStatus.State.RUNNING;
            });
            assertEquals(addresses, status.addresses());
            nativeProbeRejectRegistrations(true);
            assertThrows(IOException.class, () -> node.logout(WAIT));
            nativeProbeRejectRegistrations(false);
            node.logout(WAIT);
            status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertNull(status.loginUrl());
            assertTrue(status.addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 120000)
    public void expiredKeyRequiresANewLogin() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-expiry-test", controlUrl);
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            addresses = await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses();

            assertNull(status.revocation());
            assertEquals(1, nativeProbeExpire());
            status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertEquals(NodeStatus.Revocation.KEY_EXPIRED, status.revocation());
            assertNull(status.loginUrl());
            assertTrue(status.toString().contains("KEY_EXPIRED"));
            // No login URL arrives by itself, and the tailnet is unreachable.
            status = node.awaitStatus(status, Duration.ofSeconds(2));
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertEquals(NodeStatus.Revocation.KEY_EXPIRED, status.revocation());
            assertNull(status.loginUrl());
            assertThrows(SocketTimeoutException.class, () -> node.dial(addresses.get(0).getHostAddress(), 7070, Duration.ofMillis(500)));

            node.login();
            status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertEquals(NodeStatus.Revocation.KEY_EXPIRED, status.revocation());
            assertTrue("Unexpected login URL", status.loginUrl().startsWith(controlUrl + "/auth/"));
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.loginUrl());
            assertNull(status.revocation());
            assertEquals(addresses, status.addresses());

            // Re-authenticating keeps the node running until the user opens the new URL.
            node.login();
            status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.RUNNING, status.state());
            assertTrue("Unexpected login URL", status.loginUrl().startsWith(controlUrl + "/auth/"));
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            // The URL is cleared once the new key is saved, so closing then keeps the login.
            status = await(node, s -> s.loginUrl() == null);
            assertEquals(NodeStatus.State.RUNNING, status.state());
            assertEquals(addresses, status.addresses());
            assertEquals("klethron-expiry-test", nativeProbeHostnames());
        }
        assertOnlyStateFile();

        // The new key persisted.
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-expiry-test", controlUrl);
            NodeStatus status = await(node, s -> {
                assertNull("Node asked to log in again", s.loginUrl());
                return s.state() == NodeStatus.State.RUNNING;
            });
            assertEquals(addresses, status.addresses());
        }
    }

    @Test(timeout = 120000)
    public void removedNodeRequiresANewLogin() throws Exception {
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-removal-test", controlUrl);
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            await(node, s -> s.state() == NodeStatus.State.RUNNING);
        }
        // testcontrol keeps streaming to a running node, so it notices at its next start.
        nativeProbeRemove(true);
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-removal-test", controlUrl);
            NodeStatus status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertEquals(NodeStatus.Revocation.NODE_REMOVED, status.revocation());
            assertNull(status.loginUrl());

            node.login();
            status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertEquals(NodeStatus.Revocation.NODE_REMOVED, status.revocation());
            nativeProbeRemove(false);
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.revocation());
            assertFalse(status.addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 90000)
    public void rejectedLoginReportsAnErrorUntilRunning() throws Exception {
        nativeProbeRejectRegistrations(true);
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-rejection-test", controlUrl);
            NodeStatus status = await(node, s -> s.error() != null);
            assertEquals("invalid authkey", status.error()); // testcontrol's text.
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertNull(status.revocation());
            assertFalse(status.toString().contains("authkey"));

            // Upstream retries; the error remains while the user logs in.
            nativeProbeRejectRegistrations(false);
            status = await(node, s -> s.loginUrl() != null);
            assertEquals("invalid authkey", status.error());
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.error());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 90000)
    public void deviceApprovalFollowsLogin() throws Exception {
        nativeProbeRequireMachineAuth();
        try (TailnetNode node = new TailnetNode()) {
            node.start(directory.toFile(), "klethron-approval-test", controlUrl);
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));
            status = await(node, s -> s.state() == NodeStatus.State.NEEDS_MACHINE_AUTH);
            assertNull(status.loginUrl());
            assertEquals(1, nativeProbeApproveMachines());
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertFalse(status.addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 60000)
    public void closeWakesWaitersAndRejectsLaterCalls() throws Exception {
        TailnetNode node = new TailnetNode();
        node.start(directory.toFile(), "klethron-close-test", controlUrl);
        NodeStatus status = await(node, s -> s.loginUrl() != null);
        CompletableFuture<Throwable> waiter = CompletableFuture.supplyAsync(() ->
                assertThrows(IllegalStateException.class, () -> node.awaitStatus(status, Duration.ofMinutes(5))));
        Thread.sleep(200); // Let the waiter block.
        node.close();
        assertNotNull(waiter.get(10, TimeUnit.SECONDS));
        node.close();
        assertThrows(IllegalStateException.class, () -> node.awaitStatus(null, WAIT));
        assertThrows(IllegalStateException.class, () -> node.start(directory.toFile(), "late", controlUrl));
    }

    @Test(timeout = 30000)
    public void refusesInvalidConfigurationAndEnvironmentAuthKeys() throws Exception {
        try (TailnetNode node = new TailnetNode()) {
            assertEquals(NodeStatus.State.NO_STATE, node.awaitStatus(null, WAIT).state());
            assertThrows(IllegalArgumentException.class, () -> node.start(new File("relative"), "host"));
            assertThrows(IllegalArgumentException.class, () -> node.start(directory.toFile(), ""));
            assertThrows(IllegalArgumentException.class, () -> node.start(directory.toFile(), "a\0b", controlUrl));
            nativeProbeSetAuthKeyEnvironment(true);
            try {
                IOException e = assertThrows(IOException.class, () -> node.start(directory.toFile(), "host", controlUrl));
                assertTrue(e.getMessage().contains("TS_AUTHKEY"));
            } finally {
                nativeProbeSetAuthKeyEnvironment(false);
            }
            // Refusals do not consume the node.
            node.start(directory.toFile(), "klethron-refusal-test", controlUrl);
        }
        try (TailnetNode node = new TailnetNode()) {
            Path file = Files.createFile(directory.getParent().resolve("not-a-directory"));
            assertThrows(IOException.class, () -> node.start(file.toFile(), "host", controlUrl));
            assertThrows(IllegalStateException.class, () -> node.start(directory.toFile(), "host", controlUrl));
        }
    }

    private static NodeStatus await(TailnetNode node, Predicate<NodeStatus> condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        NodeStatus status = null;
        do {
            status = node.awaitStatus(status, WAIT);
            if (condition.test(status)) return status;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out at " + status);
    }

    static Path temporaryDirectory(String prefix) throws Exception {
        return Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getNoBackupFilesDir().toPath(), prefix);
    }

    static void deleteDirectory(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private void assertOnlyStateFile() throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(List.of(directory.resolve("tailscaled.state")), files.toList());
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("tailscaled.state"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    /// Without interactive login, nodes join at once and MagicDNS serves `tail-scale.ts.net`.
    static native String nativeProbeStart(boolean interactive);

    /// Returns the IPv4 address of a peer that echoes on port 7070, greets on 7071, floods 48 MiB of a
    /// pattern (byte `i` is `i % 251`) on 7073 and on 7074 reads that pattern once released.
    static native String nativeProbeStartPeer(byte[] directory);

    /// Bytes the greeting or released stalled port received before the dialer closed, -1 on error,
    /// -2 on timeout, -3 if a stalled port received a byte outside the pattern.
    static native long nativeProbeAwaitReceived(long timeoutMillis);

    /// Bytes the flooding port has written.
    static native long nativeProbeFlooded();

    /// Lets the stalled port read.
    static native void nativeProbeRelease();

    private static native boolean nativeProbeComplete(byte[] loginUrl);

    static native void nativeProbeStop();

    /// Makes control answer map requests as for deleted nodes, or no longer.
    private static native void nativeProbeRemove(boolean removed);

    /// Expires the keys of all registered nodes; returns their number.
    private static native int nativeProbeExpire();

    /// Makes control require an administrator to approve devices. Call before starting nodes.
    private static native void nativeProbeRequireMachineAuth();

    /// Approves all registered devices; returns their number.
    private static native int nativeProbeApproveMachines();

    /// The sorted hostnames that registered devices last reported, separated by newlines.
    private static native String nativeProbeHostnames();

    private static native void nativeProbeRejectRegistrations(boolean reject);

    private static native void nativeProbeSetAuthKeyEnvironment(boolean set);
}
