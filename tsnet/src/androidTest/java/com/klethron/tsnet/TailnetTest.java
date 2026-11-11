package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Two in-process nodes join a loopback test control server and DERP/STUN server, then exchange
 * TCP data over the tailnet. No real tailnet, credentials or external network are involved.
 */
public final class TailnetTest {
    @Test(timeout = 90000)
    public void exchangesDataOverDirectPath() throws Exception {
        assertEquals("ok", LoginTest.callWithoutFiles("tailnet"));
    }

    @Test(timeout = 90000)
    public void exchangesDataOverDerpRelayOnly() throws Exception {
        assertEquals("ok", LoginTest.callWithoutFiles("tailnetDerp"));
    }
}
