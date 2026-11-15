package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/// Two local nodes with loopback control, DERP and STUN servers.
public final class TailnetTest {
    @Test(timeout = 90000)
    public void exchangesDataOverDirectPath() {
        assertEquals("ok", Probe.call("tailnet"));
    }

    @Test(timeout = 90000)
    public void exchangesDataOverDerp() {
        assertEquals("ok", Probe.call("tailnetDerp"));
    }
}
