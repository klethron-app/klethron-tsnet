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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
    static final Duration WAIT = Duration.ofSeconds(30);
    private static final String HOSTNAME = "klethron-login-test";

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
        deleteDirectory(Objects.requireNonNull(directory.getParent()));
    }

    @Test(timeout = 90000)
    public void logsInInteractivelyAndResumesFromState() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = start()) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            assertEquals(NodeStatus.State.NEEDS_LOGIN, status.state());
            assertNull(status.revocation());
            assertNull(status.error());
            assertTrue(status.addresses().isEmpty());
            assertFalse(status.toString().contains("/auth/"));
            complete(status);

            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertNull(status.loginUrl());
            addresses = status.addresses();
            assertTrue(addresses.get(0) instanceof Inet4Address);
            assertEquals(100, Byte.toUnsignedInt(((Inet4Address) addresses.get(0)).getAddress()[0]));
            // Without MagicDNS, testcontrol names the device as requested. A rename is a status
            // change, also without peers.
            assertEquals(HOSTNAME, status.dnsName());
            Probe.call("rename", "klethron-renamed");
            await(node, s -> "klethron-renamed".equals(s.dnsName()));
        }
        assertOnlyStateFile();

        // The persisted identity resumes without another login.
        try (TailnetNode node = start()) {
            awaitResumed(node, addresses);
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 120000)
    public void logoutRequiresANewLogin() throws Exception {
        String firstUrl;
        try (TailnetNode node = start()) {
            NodeStatus status = await(node, s -> s.loginUrl() != null);
            firstUrl = Objects.requireNonNull(status.loginUrl());
            node.logout(WAIT); // Not logged in: no effect, so the pending login completes.
            complete(status);
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());

            assertThrows(SocketTimeoutException.class, () -> node.logout(Duration.ZERO));
            node.logout(WAIT);
            // The names clear after the state changes.
            status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN && s.dnsName() == null);
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
            complete(status);
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());
            // The re-login's registration uses tsnet's hostname again.
            assertEquals(HOSTNAME, Probe.call("hostnames"));
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
        try (TailnetNode node = start()) {
            NodeStatus status = await(node, s -> {
                assertTrue("Logged-out node resumed", s.state() != NodeStatus.State.RUNNING);
                return s.loginUrl() != null;
            });
            assertFalse("Login URL was reused", firstUrl.equals(status.loginUrl()));
            complete(status);
            assertFalse(await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 120000)
    public void failedLogoutKeepsTheLoginForRetries() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = start()) {
            complete(await(node, s -> s.loginUrl() != null));
            addresses = await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses();
            Probe.call("rejectRegistrations", true);
            assertThrows(IOException.class, () -> node.logout(WAIT));
            NodeStatus status = await(node, s -> s.state() == NodeStatus.State.STOPPED);
            assertEquals(addresses, status.addresses());
        }
        assertOnlyStateFile();

        // The login persisted: a new node resumes it. Once control accepts, the same node that
        // failed to log out retries successfully. Unpatched upstream (#22248) forgets the in-memory
        // node key at the first failure, so the retry fails.
        Probe.call("rejectRegistrations", false);
        try (TailnetNode node = start()) {
            awaitResumed(node, addresses);
            Probe.call("rejectRegistrations", true);
            assertThrows(IOException.class, () -> node.logout(WAIT));
            Probe.call("rejectRegistrations", false);
            node.logout(WAIT);
            NodeStatus status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertNull(status.loginUrl());
            assertTrue(status.addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 120000)
    public void expiredKeyRequiresANewLogin() throws Exception {
        List<InetAddress> addresses;
        try (TailnetNode node = start()) {
            complete(await(node, s -> s.loginUrl() != null));
            addresses = await(node, s -> s.state() == NodeStatus.State.RUNNING).addresses();

            assertEquals("1", Probe.call("expire"));
            NodeStatus status = await(node, s -> s.state() == NodeStatus.State.NEEDS_LOGIN);
            assertEquals(NodeStatus.Revocation.KEY_EXPIRED, status.revocation());
            assertNull(status.loginUrl());
            assertTrue(status.toString().contains("KEY_EXPIRED"));
            assertEquals(HOSTNAME, status.dnsName());
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
            // Control stages the replacement key alongside the old key for the same device.
            assertEquals(HOSTNAME, Probe.call("hostnames"));
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
            assertEquals(HOSTNAME, Probe.call("hostnames"));
            complete(status);
            // The URL is cleared once the new key is saved, so closing then keeps the login.
            status = await(node, s -> s.loginUrl() == null);
            assertEquals(NodeStatus.State.RUNNING, status.state());
            assertEquals(addresses, status.addresses());
            assertEquals(HOSTNAME, Probe.call("hostnames"));
        }
        assertOnlyStateFile();

        // The new key persisted.
        try (TailnetNode node = start()) {
            awaitResumed(node, addresses);
        }
    }

    @Test(timeout = 120000)
    public void removedNodeRequiresANewLogin() throws Exception {
        try (TailnetNode node = start()) {
            complete(await(node, s -> s.loginUrl() != null));
            await(node, s -> s.state() == NodeStatus.State.RUNNING);
        }
        // testcontrol keeps streaming to a running node, so it notices at its next start.
        Probe.call("remove", true);
        try (TailnetNode node = start()) {
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
        try (TailnetNode node = start()) {
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
        try (TailnetNode node = start()) {
            complete(await(node, s -> s.loginUrl() != null));
            NodeStatus status = await(node, s -> s.state() == NodeStatus.State.NEEDS_MACHINE_AUTH);
            assertNull(status.loginUrl());
            assertEquals("1", Probe.call("approveMachines"));
            status = await(node, s -> s.state() == NodeStatus.State.RUNNING);
            assertFalse(status.addresses().isEmpty());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 30000)
    public void refusesInvalidConfigurationAndEnvironmentAuthKeys() throws Exception {
        File relative = new File("relative");
        File state = directory.toFile();
        assertThrows(IllegalArgumentException.class, () -> TailnetNode.start(CONTEXT, relative, "host"));
        assertThrows(IllegalArgumentException.class, () -> TailnetNode.start(CONTEXT, state, ""));
        assertThrows(IllegalArgumentException.class, () -> TailnetNode.start(CONTEXT, state, "a\0b", controlUrl));
        Probe.call("setAuthKeyEnvironment", true);
        try {
            IOException e = assertThrows(IOException.class, () -> TailnetNode.start(CONTEXT, state, "host", controlUrl));
            assertTrue(Objects.requireNonNull(e.getMessage()).contains("TS_AUTHKEY"));
        } finally {
            Probe.call("setAuthKeyEnvironment", false);
        }
        File file = Files.createFile(directory.resolveSibling("not-a-directory")).toFile();
        assertThrows(IOException.class, () -> TailnetNode.start(CONTEXT, file, "host", controlUrl));
    }

    @CanIgnoreReturnValue
    static NodeStatus await(TailnetNode node, Predicate<NodeStatus> condition) throws IOException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        NodeStatus status = null;
        do {
            status = node.awaitStatus(status, Duration.ofNanos(deadline - System.nanoTime()));
            if (condition.test(status)) return status;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out at " + status);
    }

    private TailnetNode start() throws IOException {
        return TailnetNode.start(CONTEXT, directory.toFile(), HOSTNAME, controlUrl);
    }

    /// Waits until a node started from saved state runs with `addresses`, without asking to log in again.
    private static void awaitResumed(TailnetNode node, List<InetAddress> addresses) throws IOException {
        assertEquals(addresses, await(node, s -> {
            assertNull("Node asked to log in again", s.loginUrl());
            return s.state() == NodeStatus.State.RUNNING;
        }).addresses());
    }

    /// Completes the pending login in place of the user's browser. Never prints the URL, even in failures.
    private void complete(NodeStatus status) {
        String url = Objects.requireNonNull(status.loginUrl());
        assertTrue("Unexpected login URL", url.startsWith(controlUrl + "/auth/"));
        assertEquals("ok", Probe.call("complete", url));
    }

    /// Calls a probe that starts nodes in a new directory, which must stay empty.
    static String callWithoutFiles(String probe) throws Exception {
        Path directory = temporaryDirectory(probe + "-🔒-");
        try {
            String result = Probe.call(probe, directory);
            try (var files = Files.list(directory)) {
                assertEquals("Probe nodes must not write files", 0, files.count());
            }
            return result;
        } finally {
            deleteDirectory(directory);
        }
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
