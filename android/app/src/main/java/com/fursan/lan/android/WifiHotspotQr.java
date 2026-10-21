package com.fursan.lan.android;

/** Builds standard Wi-Fi QR payloads for a locally-created hotspot. */
final class WifiHotspotQr {
    private WifiHotspotQr() { }

    static String payload(String ssid, String passphrase) {
        if (ssid == null || ssid.trim().isEmpty()) throw new IllegalArgumentException("اسم شبكة Wi-Fi فارغ.");
        String escapedSsid = escape(ssid);
        if (passphrase == null || passphrase.isEmpty()) return "WIFI:T:nopass;S:" + escapedSsid + ";;";
        return "WIFI:T:WPA;S:" + escapedSsid + ";P:" + escape(passphrase) + ";;";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace(":", "\\:").replace("\"", "\\\"");
    }
}
