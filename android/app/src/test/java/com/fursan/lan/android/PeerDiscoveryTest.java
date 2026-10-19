package com.fursan.lan.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerDiscoveryTest {
    @Test
    public void acceptsCurrentPeerProtocolAndPort() {
        assertTrue(PeerDiscovery.isCompatible("fursan-p2p-v1", 43821));
    }

    @Test
    public void acceptsAndroidFramePeerProtocol() {
        assertTrue(PeerDiscovery.isCompatible(WifiPeerService.PROTOCOL, WifiPeerService.PEER_PORT));
    }

    @Test
    public void parsesAndroidFrameAdvertisementWithoutTreatingItAsSocketIo() throws Exception {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        byte[] packet = ("{\"protocol\":\"" + WifiPeerService.PROTOCOL + "\",\"port\":43821,"
                + "\"transport\":\"wifi-frame-v1\",\"name\":\"هاتف\",\"instanceId\":\"" + id + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PeerDiscovery.PeerAdvertisement peer = PeerDiscovery.parseAdvertisement(packet, packet.length, "192.168.49.2");
        assertNotNull(peer);
        assertEquals("wifi-frame-v1", peer.transport);
        assertEquals(id, peer.instanceId);
        assertEquals("هاتف", peer.name);
    }

    @Test
    public void parsesWindowsAdvertisementAsSocketIo() throws Exception {
        byte[] packet = "{\"protocol\":\"fursan-p2p-v1\",\"port\":43821,\"name\":\"Windows\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PeerDiscovery.PeerAdvertisement peer = PeerDiscovery.parseAdvertisement(packet, packet.length, "192.168.1.4");
        assertNotNull(peer);
        assertEquals("socketio", peer.transport);
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
