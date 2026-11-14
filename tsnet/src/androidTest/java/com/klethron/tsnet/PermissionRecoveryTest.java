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
}
