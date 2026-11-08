package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
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
        assertTrue("Nodes must start off the UI thread", Looper.myLooper() != Looper.getMainLooper());
        controlUrl = nativeProbeStart();
        assertTrue(controlUrl.startsWith("http://127.0.0.1:"));
        directory = StartupTest.temporaryDirectory("login-🔒-").resolve("node");
    }

    @After
    public void stopControl() throws Exception {
        nativeProbeStop();
        StartupTest.deleteDirectory(directory.getParent());
    }

    @Test(timeout = 90000)
    public void logsInInteractivelyAndResumesFromState() throws Exception {
        List<InetAddress> addresses;
        try (TsnetNode node = new TsnetNode()) {
            node.start(directory.toFile(), "klethron-login-test", controlUrl);
            assertThrows(IllegalStateException.class, () -> node.start(directory.toFile(), "again", controlUrl));
            TsnetStatus status = await(node, s -> s.loginUrl() != null);
            assertEquals(TsnetStatus.State.NEEDS_LOGIN, status.state());
            assertTrue(status.addresses().isEmpty());
            // Never print login URLs, even in test failures.
            assertTrue("Unexpected login URL", status.loginUrl().startsWith(controlUrl + "/auth/"));
            assertFalse(status.toString().contains("/auth/"));
            assertTrue(nativeProbeComplete(status.loginUrl().getBytes(StandardCharsets.UTF_8)));

            status = await(node, s -> s.state() == TsnetStatus.State.RUNNING);
            assertNull(status.loginUrl());
            addresses = status.addresses();
            assertTrue(addresses.get(0) instanceof Inet4Address);
            assertEquals(100, Byte.toUnsignedInt(((Inet4Address) addresses.get(0)).getAddress()[0]));
            // Nothing changed: the timeout returns the same status.
            assertEquals(status.version, node.awaitStatus(status, Duration.ofMillis(100)).version);
        }
        assertOnlyStateFile();

        // The persisted identity resumes without another login.
        try (TsnetNode node = new TsnetNode()) {
            node.start(directory.toFile(), "klethron-login-test", controlUrl);
            TsnetStatus status = await(node, s -> {
                assertNull("Resumed node asked to log in again", s.loginUrl());
                return s.state() == TsnetStatus.State.RUNNING;
            });
            assertEquals(addresses, status.addresses());
        }
        assertOnlyStateFile();
    }

    @Test(timeout = 60000)
    public void closeWakesWaitersAndRejectsLaterCalls() throws Exception {
        TsnetNode node = new TsnetNode();
        node.start(directory.toFile(), "klethron-close-test", controlUrl);
        TsnetStatus status = await(node, s -> s.loginUrl() != null);
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
        try (TsnetNode node = new TsnetNode()) {
            assertEquals(TsnetStatus.State.NO_STATE, node.awaitStatus(null, WAIT).state());
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
        try (TsnetNode node = new TsnetNode()) {
            Path file = Files.createFile(directory.getParent().resolve("not-a-directory"));
            assertThrows(IOException.class, () -> node.start(file.toFile(), "host", controlUrl));
            assertThrows(IllegalStateException.class, () -> node.start(directory.toFile(), "host", controlUrl));
        }
    }

    private static TsnetStatus await(TsnetNode node, Predicate<TsnetStatus> condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        TsnetStatus status = null;
        do {
            status = node.awaitStatus(status, WAIT);
            if (condition.test(status)) return status;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out at " + status);
    }

    private void assertOnlyStateFile() throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(List.of(directory.resolve("tailscaled.state")), files.toList());
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("tailscaled.state"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    private static native String nativeProbeStart();

    private static native boolean nativeProbeComplete(byte[] loginUrl);

    private static native void nativeProbeStop();

    private static native void nativeProbeSetAuthKeyEnvironment(boolean set);
}
