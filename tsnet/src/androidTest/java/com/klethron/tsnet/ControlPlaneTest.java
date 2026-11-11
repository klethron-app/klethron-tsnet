package com.klethron.tsnet;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contacts Tailscale's real control plane without credentials, so this needs a working internet
 * connection. The node stops at an unauthorized login request; the URL never leaves native code.
 */
public final class ControlPlaneTest {
    @Test(timeout = 60000)
    public void reachesRealControlPlaneUntilLoginWithoutWritingFiles() throws Exception {
        String root = LoginTest.callWithoutFiles("control");
        assertTrue("The verified control chain must end at an Android system root, not " + root,
                TrustRootsTest.systemRootFingerprints().contains(root));
    }
}
