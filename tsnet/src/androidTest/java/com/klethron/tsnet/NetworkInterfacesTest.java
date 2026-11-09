package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class NetworkInterfacesTest {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    @Test
    public void nativeInterfacesMatchAndroidWhereGoDiscoveryIsDenied() throws Exception {
        JSONObject report = new JSONObject(nativeProbe());
        assertEquals("", report.getString("Error"));
        // A negative control, independent of the registered getter. Needs SELinux Enforcing.
        assertTrue(report.getString("GoError"), report.getString("GoError").contains("permission denied"));
        Set<String> actual = new TreeSet<>();
        JSONArray interfaces = report.getJSONArray("Interfaces");
        for (int i = 0; i < interfaces.length(); i++) {
            actual.add(interfaces.getString(i));
        }
        Set<String> expected = new TreeSet<>();
        boolean hasIPv4 = false;
        boolean hasIPv6 = false;
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InterfaceAddress address : iface.getInterfaceAddresses()) {
                byte[] ip = address.getAddress().getAddress();
                hasIPv4 |= ip.length == 4;
                hasIPv6 |= ip.length == 16;
                expected.add(HexFormat.of().formatHex(iface.getName().getBytes(StandardCharsets.UTF_8))
                        + "|" + iface.getIndex() + "|" + iface.getMTU()
                        + "|" + iface.isUp() + "|" + iface.isLoopback()
                        + "|" + iface.isPointToPoint() + "|" + iface.supportsMulticast()
                        + "|" + HexFormat.of().formatHex(ip)
                        + "/" + address.getNetworkPrefixLength());
            }
        }
        assertTrue("Expected IPv4 and IPv6 addresses", hasIPv4 && hasIPv6);
        assertEquals(expected, actual);
    }

    private static native String nativeProbe();
}
