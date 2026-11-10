package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Two in-process nodes join a loopback test control server and DERP/STUN server, then exchange
 * TCP data over the tailnet. No real tailnet, credentials or external network are involved.
 */
public final class TailnetTest {
    @Test(timeout = 90000)
    public void exchangesDataOverDirectPath() throws Exception {
        assertExchange(false);
    }

    @Test(timeout = 90000)
    public void exchangesDataOverDerpRelayOnly() throws Exception {
        assertExchange(true);
    }

    private static void assertExchange(boolean relayOnly) throws Exception {
        Path directory = LoginTest.temporaryDirectory("tailnet-🔒-");
        try {
            String report = Probe.call(relayOnly ? "tailnetDerp" : "tailnet", directory);
            assertEquals("ok", report);
            try (var files = Files.list(directory)) {
                assertEquals("Nodes must not write log configuration, buffers or node state", 0, files.count());
            }
        } finally {
            LoginTest.deleteDirectory(directory);
        }
    }
}
