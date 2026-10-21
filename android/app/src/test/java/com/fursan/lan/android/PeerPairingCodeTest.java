package com.fursan.lan.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class PeerPairingCodeTest {
    private static final String INSTANCE = "8e81cc3a-a9f5-4e81-bd42-c697c74d45a0";

    @Test
    public void createsAndParsesAnOfflineLocalPeerCode() throws Exception {
        JSONObject code = PeerPairingCode.create("192.168.49.1", INSTANCE);
        assertEquals("fursan-lan-peer", code.getString("protocol"));
        assertEquals(43821, code.getInt("port"));
        assertEquals("192.168.49.1", PeerPairingCode.parseAddress(code.toString()));
    }

    @Test
    public void rejectsUnknownProtocolAndWrongPort() throws Exception {
        JSONObject code = PeerPairingCode.create("192.168.1.8", INSTANCE);
        code.put("protocol", "other-app");
        assertInvalid(code.toString());
        code.put("protocol", PeerPairingCode.PROTOCOL).put("port", 443);
        assertInvalid(code.toString());
    }

    @Test
    public void rejectsPublicMalformedAndNonIpv4Addresses() throws Exception {
        JSONObject code = PeerPairingCode.create("192.168.1.8", INSTANCE);
        code.put("address", "8.8.8.8");
        assertInvalid(code.toString());
        code.put("address", "localhost");
        assertInvalid(code.toString());
        code.put("address", "192.168.1.999");
        assertInvalid(code.toString());
    }

    @Test
    public void rejectsInvalidPeerIdentity() throws Exception {
        JSONObject code = PeerPairingCode.create("10.0.0.8", INSTANCE);
        code.put("instanceId", "not-a-uuid");
        assertInvalid(code.toString());
    }

    private static void assertInvalid(String raw) throws Exception {
        try {
            PeerPairingCode.parseAddress(raw);
            fail("Expected pairing code to be rejected");
        } catch (JSONException expected) { }
    }
}
