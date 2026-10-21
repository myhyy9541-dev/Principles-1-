package com.fursan.lan.android;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class WifiHotspotQrTest {
    @Test public void buildsProtectedNetworkPayload() {
        assertEquals("WIFI:T:WPA;S:AndroidShare;P:12345678;;",
                WifiHotspotQr.payload("AndroidShare", "12345678"));
    }

    @Test public void escapesSpecialWifiQrCharacters() {
        assertEquals("WIFI:T:WPA;S:lan\\;east;P:p\\:a\\,s\\\\s\\\"w;;",
                WifiHotspotQr.payload("lan;east", "p:a,s\\s\"w"));
    }

    @Test public void supportsOpenNetworkPayload() {
        assertEquals("WIFI:T:nopass;S:OpenLAN;;", WifiHotspotQr.payload("OpenLAN", ""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptySsid() {
        WifiHotspotQr.payload("  ", "password");
    }
}
