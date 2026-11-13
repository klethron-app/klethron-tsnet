package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SecurityTest {
    @Test
    public void loginUrlsRequireTheExpectedOriginAndTransport() {
        String control = "https://controlplane.tailscale.com";
        for (String url : new String[] {
                "http://login.tailscale.com/a/test", "https://attacker.example/a/test",
                "https://tailscale.com.attacker.example/a/test", "https://attacker.example/?tailscale.com",
                "https://user:password@login.tailscale.com/a/test", "https://:443/a/test",
                "https://login.tailscale.com/a/\ntest", "https://login.tailscale.com/a/ test",
                "javascript:alert(1)", "intent://login.tailscale.com/a/test", "//login.tailscale.com/a/test"}) {
            assertEquals("rejected", Probe.call("loginURL", control + '\0' + url));
        }
        for (String url : new String[] {"https://login.tailscale.com/a/test", "https://tailscale.com/a/test"}) {
            assertEquals("accepted", Probe.call("loginURL", control + '\0' + url));
        }
        assertEquals("accepted", Probe.call("loginURL", "http://127.0.0.1:1234\0http://127.0.0.1:1234/auth/test"));
        assertEquals("accepted", Probe.call("loginURL", "http://[::1]:1234\0http://[::1]:1234/auth/test"));
        assertEquals("rejected", Probe.call("loginURL", "http://127.0.0.1:1234\0http://127.0.0.1:5678/auth/test"));
        assertEquals("rejected", Probe.call("loginURL", "http://127.0.0.1:1234\0http://attacker.example/auth/test"));
        assertEquals("rejected", Probe.call("loginURL", "http://control.example\0http://control.example/auth/test"));
    }

    @Test(timeout = 30000)
    public void missedStatusUpdatesEndExistingAccess() {
        assertEquals("ok", Probe.call("watchDisconnect", "eof"));
        assertEquals("ok", Probe.call("watchDisconnect", "lag"));
    }
}
