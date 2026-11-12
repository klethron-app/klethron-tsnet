package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public final class NetworkInterfacesTest {
    @Test
    public void nativeInterfacesMatchAndroidWhereGoDiscoveryIsDenied() throws Exception {
        Set<String> expected = Set.of();
        Set<String> actual = null;
        // Retry: addresses can change between the snapshots, as when Wi-Fi reconnects after NetworkChangeTest.
        for (int attempt = 0; attempt < 50 && !expected.equals(actual); attempt++) {
            if (attempt > 0) Thread.sleep(100);
            List<String> report = Probe.call("interfaces").lines().toList();
            // A negative control, independent of the registered getter. Needs SELinux Enforcing.
            assertTrue(report.get(0), report.get(0).contains("permission denied"));
            actual = new TreeSet<>(report.subList(1, report.size()));
            expected = new TreeSet<>();
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress address : iface.getInterfaceAddresses()) {
                    expected.add(HexFormat.of().formatHex(iface.getName().getBytes(StandardCharsets.UTF_8))
                            + "|" + iface.getIndex() + "|" + iface.getMTU()
                            + "|" + iface.isUp() + "|" + iface.isLoopback()
                            + "|" + iface.isPointToPoint() + "|" + iface.supportsMulticast()
                            + "|" + HexFormat.of().formatHex(address.getAddress().getAddress())
                            + "/" + address.getNetworkPrefixLength());
                }
            }
        }
        assertEquals(expected, actual);
    }
}
