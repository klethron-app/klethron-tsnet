package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Two in-process nodes join a loopback test control server and DERP/STUN server, then exchange
 * TCP data over the tailnet. No real tailnet, credentials or external network are involved.
 */
public final class TailnetTest {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    @Test(timeout = 90000)
    public void exchangesDataOverDirectPath() throws Exception {
        assertExchange(false);
    }

    @Test(timeout = 90000)
    public void exchangesDataOverDerpRelayOnly() throws Exception {
        assertExchange(true);
    }

    private static void assertExchange(boolean relayOnly) throws Exception {
        assertTrue("Networking must run off the UI thread", Looper.myLooper() != Looper.getMainLooper());
        Path directory = StartupTest.temporaryDirectory("tailnet-🔒-");
        try {
            String report = nativeProbe(directory.toString().getBytes(StandardCharsets.UTF_8), relayOnly);
            assertEquals("ok", report);
            try (var files = Files.list(directory)) {
                assertEquals("Nodes must not write log configuration, buffers or node state", 0, files.count());
            }
        } finally {
            StartupTest.deleteDirectory(directory);
        }
    }

    private static native String nativeProbe(byte[] directory, boolean relayOnly);
}
