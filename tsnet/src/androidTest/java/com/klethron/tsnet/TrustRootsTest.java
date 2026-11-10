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
    @Test
    public void goTrustsExactlyAndroidSystemRoots() throws Exception {
        Set<String> expected = systemRootFingerprints();
        assertTrue("Expected Android system roots", expected.size() > 100);
        assertEquals(expected, new TreeSet<>(List.of(Probe.call("roots").split("\n"))));
    }

    /** SHA-256 hex fingerprints of Android's current system roots, without user-disabled roots. */
    static Set<String> systemRootFingerprints() throws Exception {
        // AndroidCAStore lists the current system store without user-disabled roots, plus user CAs.
        KeyStore store = KeyStore.getInstance("AndroidCAStore");
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
