package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PermissionRecoveryTest {
    @Test(timeout = 30000)
    public void udpRecoveryKeepsPendingRelayAndHandshake() {
        assertEquals("ok", Probe.call("permissionRecovery", "pending"));
    }

    @Test(timeout = 30000)
    public void udpRecoveryKeepsConnectedRelay() {
        assertEquals("ok", Probe.call("permissionRecovery", "connected"));
    }

    @Test(timeout = 30000)
    public void networkChangeReplacesPendingRelay() {
        assertEquals("ok", Probe.call("permissionRecovery", "network-change"));
    }

    @Test(timeout = 30000)
    public void brokenUdpSocketTriggersFullRecovery() {
        assertEquals("ok", Probe.call("permissionRecovery", "broken-pipe"));
    }

    @Test(timeout = 30000)
    public void ipv6OnlyNetcheckKeepsRelayAndQueue() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-no-v4"));
    }

    @Test(timeout = 30000)
    public void successfulIpv4NetcheckKeepsRelayAndQueue() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-sent"));
    }

    @Test(timeout = 30000)
    public void failedIpv4NetcheckRecoversSocket() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-failed"));
    }

    @Test(timeout = 30000)
    public void deniedIpv4ProbeCountsAsAttempt() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-denied"));
    }
}
