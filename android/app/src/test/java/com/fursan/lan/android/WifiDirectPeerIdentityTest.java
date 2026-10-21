package com.fursan.lan.android;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class WifiDirectPeerIdentityTest {
    @Test public void createsAndParsesFursanServiceRecord() {
        String id = "w0123456789abcdef0123456789abcdef";
        Map<String, String> record = WifiDirectPeerIdentity.createRecord(id, "هاتف التجربة");

        WifiDirectPeerIdentity parsed = WifiDirectPeerIdentity.parse(record);

        assertEquals(id, parsed.clientId);
        assertEquals("هاتف التجربة", parsed.name);
        assertTrue(WifiDirectPeerIdentity.serviceInstance(id).startsWith("fursan-"));
    }

    @Test public void rejectsOtherServicesAndMalformedIds() {
        Map<String, Object> record = new HashMap<>();
        record.put("app", "another-app-v1");
        record.put("clientid", "w0123456789abcdef0123456789abcdef");
        assertNull(WifiDirectPeerIdentity.parse(record));

        record.put("app", "fursanlan-v1");
        record.put("clientid", "not a valid peer");
        assertNull(WifiDirectPeerIdentity.parse(record));
    }

    @Test(expected = IllegalArgumentException.class)
    public void doesNotAdvertiseInvalidLocalId() {
        WifiDirectPeerIdentity.createRecord("bad-id", "هاتف");
    }
}
