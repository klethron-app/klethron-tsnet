package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Contacts Tailscale's real control plane without credentials, so this needs a working internet
 * connection. The node stops at an unauthorized login request; the URL never leaves native code.
 */
public final class ControlPlaneTest {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    @Test(timeout = 60000)
    public void reachesRealControlPlaneUntilLoginWithoutWritingFiles() throws Exception {
        Path directory = LoginTest.temporaryDirectory("control-");
        try {
            JSONObject report = new JSONObject(nativeProbe(directory.toString().getBytes(StandardCharsets.UTF_8)));
            assertEquals("ok", report.getString("Result"));
            assertTrue("The verified control chain must end at an Android system root",
                    TrustRootsTest.systemRootFingerprints().contains(report.getString("Root")));
            try (var files = Files.list(directory)) {
                assertEquals("Startup until login must not write files", 0, files.count());
            }
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }

    private static native String nativeProbe(byte[] directory);
}
