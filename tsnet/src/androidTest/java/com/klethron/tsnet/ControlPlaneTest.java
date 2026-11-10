package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Contacts Tailscale's real control plane without credentials, so this needs a working internet
 * connection. The node stops at an unauthorized login request; the URL never leaves native code.
 */
public final class ControlPlaneTest {
    @Test(timeout = 60000)
    public void reachesRealControlPlaneUntilLoginWithoutWritingFiles() throws Exception {
        Path directory = LoginTest.temporaryDirectory("control-");
        try {
            String root = Probe.call("control", directory);
            assertTrue("The verified control chain must end at an Android system root, not " + root,
                    TrustRootsTest.systemRootFingerprints().contains(root));
            try (var files = Files.list(directory)) {
                assertEquals("Startup until login must not write files", 0, files.count());
            }
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }
}
