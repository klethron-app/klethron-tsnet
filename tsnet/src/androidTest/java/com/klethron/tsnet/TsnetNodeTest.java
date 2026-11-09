package com.klethron.tsnet;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TsnetNodeTest {
    @Test
    public void reportsLinkedTailscaleVersion() {
        assertTrue(TsnetNode.tailscaleVersion().startsWith("v1."));
        assertTrue(TsnetNode.tailscaleVersion().endsWith("-62d055f4c38b"));
    }

    @Test
    public void closesNativeNodesRepeatedly() {
        for (int i = 0; i < 100; i++) {
            try (TsnetNode node = new TsnetNode()) {
                node.close();
            }
        }
    }
}
