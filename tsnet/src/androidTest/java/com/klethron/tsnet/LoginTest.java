package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
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
    static final Context CONTEXT = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final Duration WAIT = Duration.ofSeconds(30);

    private String controlUrl;
    private Path directory;

    @Before
    public void startControl() throws Exception {
        controlUrl = Probe.call("startControl", true);
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        directory = temporaryDirectory("login-🔒-").resolve("node");
    }

    @After
    public void stopControl() throws Exception {
        Probe.call("stopControl");
        deleteDirectory(directory.getParent());
    }

    @Test(timeout = 90000)
    public void logsInInteractivelyAndResumesFromState() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-login-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertNull(status.revocation());
            assertNull(status.error());
            assertTrue(status.addresses().isEmpty());
            // Never print login URLs, even in test failures.
            assertTrue("Unexpected login URL", status.loginUrl().startsWith(controlUrl + "/auth/"));
            assertFalse(status.toString().contains("/auth/"));
            complete(status);

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
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-login-test", controlUrl)) {
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
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-logout-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            firstUrl = status.loginUrl();
            node.logout(WAIT); // Not logged in: no effect.
            assertEquals(firstUrl, node.awaitStatus(null, Duration.ZERO).loginUrl());
            complete(status);
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
            complete(status);
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());
            // The re-login's registration uses tsnet's hostname again.
            assertEquals("klethron-logout-test", Probe.call("hostnames"));
            // Logout discards a pending re-authentication URL with the key it would authorize.
            node.login();
            await(node, s -> s.loginUrl() != null);
            node.logout(WAIT);
            status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertNull(status.loginUrl());
            assertTrue(status.addresses().isEmpty());
        }
        assertOnlyStateFile();

        // The logout persists: the directory no longer resumes, and a new login is offered.
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-logout-test", controlUrl)) {
            NodeStatus status = await(node, s -> {
                assertTrue("Logged-out node resumed", s.state() != NodeStatus.State.RUNNING);
                return s.loginUrl() != null;
            });
            assertFalse("Login URL was reused", firstUrl.equals(status.loginUrl()));
            complete(status);
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());
            node.close();
            assertThrows(ClosedChannelException.class, () -> node.logout(WAIT));
            assertThrows(ClosedChannelException.class, node::login);
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 120000)
    public void failedLogoutKeepsTheLoginForRetries() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-logout-failure-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            complete(status);
            addresses = await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses();
            Probe.call("rejectRegistrations", true);
            assertThrows(IOException.class, () -> node.logout(WAIT));
            status = await(node, s -> s.state() == NodeStatus.State.STOPPED);
            assertEquals(addresses, status.addresses());
            // Let netmap updates arrive: upstream saved an empty node key over the login here.
            assertEquals(status.version, node.awaitStatus(status, Duration.ofSeconds(3)).version);
        }
        assertOnlyStateFile();

        // The login persisted: a new node resumes it. Once control accepts, the same node that
        // failed to log out retries successfully.
        Probe.call("rejectRegistrations", false);
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-logout-failure-test", controlUrl)) {
            NodeStatus status = await(node, s -> {
                assertNull("Node asked to log in again", s.loginUrl());
                return s.state() == NodeStatus.State.RUNNING;
            });
            assertEquals(addresses, status.addresses());
            Probe.call("rejectRegistrations", true);
            assertThrows(IOException.class, () -> node.logout(WAIT));
            Probe.call("rejectRegistrations", false);
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
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-expiry-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            complete(status);
            addresses = await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses();

            assertEquals("1", Probe.call("expire"));
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
            // Control stages the replacement key alongside the old key for the same device.
            assertEquals("klethron-expiry-test", Probe.call("hostnames"));
            complete(status);
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.loginUrl());
            assertNull(status.revocation());
            assertEquals(addresses, status.addresses());

            // Re-authenticating keeps the node running until the user opens the new URL.
            node.login();
            status = await(node, s -> {
                assertEquals("Re-authentication interrupted the node", NodeStatus.State.RUNNING, s.state());
                return s.loginUrl() != null;
            });
            assertTrue("Unexpected login URL", status.loginUrl().startsWith(controlUrl + "/auth/"));
            assertEquals("klethron-expiry-test", Probe.call("hostnames"));
            complete(status);
            // The URL is cleared once the new key is saved, so closing then keeps the login.
            status = await(node, s -> s.loginUrl() == null);
            assertEquals(NodeStatus.State.RUNNING, status.state());
            assertEquals(addresses, status.addresses());
            assertEquals("klethron-expiry-test", Probe.call("hostnames"));
        }
        assertOnlyStateFile();

        // The new key persisted.
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-expiry-test", controlUrl)) {
            NodeStatus status = await(node, s -> {
                assertNull("Node asked to log in again", s.loginUrl());
                return s.state() == NodeStatus.State.RUNNING;
            });
            assertEquals(addresses, status.addresses());
        }
    }

    @Test(timeout = 120000)
    public void removedNodeRequiresANewLogin() throws Exception {
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-removal-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            complete(status);
            await(node, s -> s.state() == NodeStatus.State.RUNNING);
        }
        // testcontrol keeps streaming to a running node, so it notices at its next start.
        Probe.call("remove", true);
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-removal-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertEquals(NodeStatus.Revocation.NODE_REMOVED, status.revocation());
            assertNull(status.loginUrl());

            node.login();
            status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertEquals(NodeStatus.Revocation.NODE_REMOVED, status.revocation());
            Probe.call("remove", false);
            complete(status);
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.revocation());
            assertFalse(status.addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 90000)
    public void rejectedLoginReportsAnErrorUntilRunning() throws Exception {
        Probe.call("rejectRegistrations", true);
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-rejection-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.error() != null);
            assertEquals("invalid authkey", status.error()); // testcontrol's text.
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertNull(status.revocation());
            assertFalse(status.toString().contains("authkey"));

            // Upstream retries; the error remains while the user logs in.
            Probe.call("rejectRegistrations", false);
            status = await(node, s -> s.loginUrl() != null);
            assertEquals("invalid authkey", status.error());
            complete(status);
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.error());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 90000)
    public void deviceApprovalFollowsLogin() throws Exception {
        Probe.call("requireMachineAuth");
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-approval-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            complete(status);
            status = await(node, s -> s.state() == NodeStatus.State.NEEDS_MACHINE_AUTH);
            assertNull(status.loginUrl());
            assertEquals("1", Probe.call("approveMachines"));
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertFalse(status.addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 60000)
    public void closeWakesWaitersAndRejectsLaterCalls() throws Exception {
        try (TailnetNode node = TailnetNode.start(CONTEXT, directory.toFile(), "klethron-close-test", controlUrl)) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            CompletableFuture<Throwable> waiter = CompletableFuture.supplyAsync(() ->
                    assertThrows(ClosedChannelException.class, () -> node.awaitStatus(status, Duration.ofMinutes(5))));
            Thread.sleep(200); // Best effort: let the waiter block.
            assertFalse(waiter.isDone());
            node.close();
            waiter.get(10, TimeUnit.SECONDS);
            node.close();
            assertThrows(ClosedChannelException.class, () -> node.awaitStatus(null, WAIT));
        }
    }

    @Test(timeout = 30000)
    public void refusesInvalidConfigurationAndEnvironmentAuthKeys() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TailnetNode.start(CONTEXT, new File("relative"), "host"));
        assertThrows(IllegalArgumentException.class, () -> TailnetNode.start(CONTEXT, directory.toFile(), ""));
        assertThrows(IllegalArgumentException.class, () -> TailnetNode.start(CONTEXT, directory.toFile(), "a\0b", controlUrl));
        Probe.call("setAuthKeyEnvironment", true);
        try {
            IOException e = assertThrows(IOException.class, () -> TailnetNode.start(CONTEXT, directory.toFile(), "host", controlUrl));
            assertTrue(e.getMessage().contains("TS_AUTHKEY"));
        } finally {
            Probe.call("setAuthKeyEnvironment", false);
        }
        Path file = Files.createFile(directory.getParent().resolve("not-a-directory"));
        assertThrows(IOException.class, () -> TailnetNode.start(CONTEXT, file.toFile(), "host", controlUrl));
        // Refusals leave no node behind that holds the directory.
        TailnetNode.start(CONTEXT, directory.toFile(), "klethron-refusal-test", controlUrl).close();
    }

    @CanIgnoreReturnValue
    static NodeStatus await(TailnetNode node, Predicate<NodeStatus> condition) throws IOException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        NodeStatus status = null;
        do {
            status = node.awaitStatus(status, WAIT);
            if (condition.test(status)) return status;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out at " + status);
    }

    /// Completes the pending login in place of the user's browser.
    private static void complete(NodeStatus status) {
        assertEquals("ok", Probe.call("complete", status.loginUrl()));
    }

    static Path temporaryDirectory(String prefix) throws Exception {
        return Files.createTempDirectory(CONTEXT.getNoBackupFilesDir().toPath(), prefix);
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
}
