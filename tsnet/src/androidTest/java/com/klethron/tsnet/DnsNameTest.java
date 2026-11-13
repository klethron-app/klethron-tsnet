package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DnsNameTest {
    @Test
    public void validatesTheFinalLabel() {
        for (String name : new String[] {"foo..", "..", "foo..com", "a".repeat(64), "com." + "a".repeat(64),
                "com." + "a".repeat(64) + "."}) {
            assertEquals(name, "invalid", Probe.call("dnsName", name));
        }
        assertEquals(".", Probe.call("dnsName", ""));
        assertEquals(".", Probe.call("dnsName", "."));
        assertEquals("example.com.", Probe.call("dnsName", "example.com"));
        assertEquals("example.com.", Probe.call("dnsName", "example.com."));
        assertEquals("a".repeat(63) + ".", Probe.call("dnsName", "a".repeat(63)));
        assertEquals("com." + "a".repeat(63) + ".", Probe.call("dnsName", "com." + "a".repeat(63) + "."));
    }
}
