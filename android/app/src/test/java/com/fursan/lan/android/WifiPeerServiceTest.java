package com.fursan.lan.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WifiPeerServiceTest {
    @Test
    public void exactlyOnePeerInitiatesForTheSamePair() {
        String first = "123e4567-e89b-12d3-a456-426614174000";
        String second = "223e4567-e89b-12d3-a456-426614174000";
        assertTrue(WifiPeerService.shouldInitiate(first, second));
        assertFalse(WifiPeerService.shouldInitiate(second, first));
    }

    @Test
    public void sameOrMissingInstanceIdsNeverInitiate() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        assertFalse(WifiPeerService.shouldInitiate(id, id));
        assertFalse(WifiPeerService.shouldInitiate(null, id));
        assertFalse(WifiPeerService.shouldInitiate(id, null));
    }
}
