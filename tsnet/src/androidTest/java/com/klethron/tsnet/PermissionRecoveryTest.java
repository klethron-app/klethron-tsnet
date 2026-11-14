package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/// UDP permission errors must preserve the independent relay transport and its pending writes.
public final class PermissionRecoveryTest {
    @Test(timeout = 30000)
    public void keepsTheInitialRelayAndQueuedHandshakeWhileRecoveringUdp() {
        assertEquals("ok", Probe.call("permissionRecovery", "pending"));
    }

    @Test(timeout = 30000)
    public void keepsAnEstablishedRelayWhileRecoveringUdp() {
        assertEquals("ok", Probe.call("permissionRecovery", "connected"));
    }

    @Test(timeout = 30000)
    public void aNetworkChangeStillReplacesAPendingRelay() {
        assertEquals("ok", Probe.call("permissionRecovery", "network-change"));
    }

    @Test(timeout = 30000)
    public void aBrokenUdpSocketKeepsTheExistingFullRecovery() {
        assertEquals("ok", Probe.call("permissionRecovery", "broken-pipe"));
    }

    @Test(timeout = 30000)
    public void ipv6OnlyNetcheckKeepsTheRelayAndPendingWrites() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-no-v4"));
    }

    @Test(timeout = 30000)
    public void successfulIpv4NetcheckKeepsTheRelayAndPendingWrites() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-sent"));
    }

    @Test(timeout = 30000)
    public void failedIpv4NetcheckStillRecoversTheSocket() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-failed"));
    }

    @Test(timeout = 30000)
    public void deniedIpv4NetcheckStillCountsAsAnAttemptOnAndroid() {
        assertEquals("ok", Probe.call("permissionRecovery", "netcheck-denied"));
    }
}
