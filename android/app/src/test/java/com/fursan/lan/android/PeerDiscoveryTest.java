package com.fursan.lan.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerDiscoveryTest {
    @Test
    public void acceptsCurrentPeerProtocolAndPort() {
        assertTrue(PeerDiscovery.isCompatible("fursan-p2p-v1", 43821));
    }

    @Test
    public void rejectsDifferentProtocol() {
        assertFalse(PeerDiscovery.isCompatible("other-protocol", 43821));
    }

    @Test
    public void rejectsUnexpectedPort() {
        assertFalse(PeerDiscovery.isCompatible("fursan-p2p-v1", 43822));
    }
}
