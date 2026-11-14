package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public final class TrustRootsTest {
    @Test(timeout = 30000)
    public void tlsAndDerpCertificateNamesUseTheSuppliedRoots() {
        assertEquals("ok", Probe.call("tlsRoots"));
    }

    @Test(timeout = 30000)
    public void dnsOverHttpsUsesTheSuppliedRoots() {
        assertEquals("ok", Probe.call("dnsRoots"));
    }

    @Test(timeout = 30000)
    public void upnpDiscoveryAndSoapUseTheSuppliedRoots() {
        assertEquals("ok", Probe.call("portmapperRoots"));
    }

    @Test
    public void goTrustsExactlyAndroidSystemRoots() throws Exception {
        Set<String> expected = systemRootFingerprints();
        assertTrue("Expected Android system roots", expected.size() > 100);
        assertEquals(expected, new TreeSet<>(List.of(Probe.call("roots").split("\n"))));
    }

    /// Contacts Tailscale's real control plane without credentials, so this needs a working internet
    /// connection. The node stops at an unauthorized login request; the URL never leaves native code.
    @Test(timeout = 60000)
    public void reachesRealControlPlaneUntilLoginWithoutWritingFiles() throws Exception {
        String root = Probe.call("control");
        assertTrue("The verified control chain must end at an Android system root, not " + root,
                systemRootFingerprints().contains(root));
    }

    /// SHA-256 hex fingerprints of Android's current system roots, without user-disabled roots.
    private static Set<String> systemRootFingerprints() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidCAStore"); // Also lists user CAs.
        store.load(null);
        Set<String> fingerprints = new TreeSet<>();
        for (String alias : Collections.list(store.aliases())) {
            if (alias.startsWith("system:")) {
                byte[] der = store.getCertificate(alias).getEncoded();
                fingerprints.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der)));
            }
        }
        return fingerprints;
    }
}
