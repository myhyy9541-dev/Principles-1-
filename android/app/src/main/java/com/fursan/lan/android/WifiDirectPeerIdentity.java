package com.fursan.lan.android;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Validates the app identity carried in local Wi-Fi Direct DNS-SD records. */
final class WifiDirectPeerIdentity {
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9_-]{16,80}$");
    private static final String APP_MARKER = "fursanlan-v1";

    final String clientId;
    final String name;

    private WifiDirectPeerIdentity(String clientId, String name) {
        this.clientId = clientId;
        this.name = name;
    }

    static Map<String, String> createRecord(String clientId, String name) {
        String safeId = clientId == null ? "" : clientId.trim();
        if (!CLIENT_ID.matcher(safeId).matches()) {
            throw new IllegalArgumentException("هوية جهاز فرسان لان غير صالحة.");
        }
        Map<String, String> record = new LinkedHashMap<>();
        record.put("app", APP_MARKER);
        record.put("clientid", safeId);
        record.put("name", sanitizeName(name));
        return record;
    }

    static WifiDirectPeerIdentity parse(Map<String, ?> record) {
        if (record == null || !APP_MARKER.equals(stringValue(record.get("app")))) return null;
        String id = stringValue(record.get("clientid"));
        if (!CLIENT_ID.matcher(id).matches()) return null;
        return new WifiDirectPeerIdentity(id, sanitizeName(stringValue(record.get("name"))));
    }

    static String serviceInstance(String clientId) {
        String safeId = clientId == null ? "" : clientId.replaceAll("[^A-Za-z0-9_-]", "");
        if (safeId.length() > 32) safeId = safeId.substring(0, 32);
        return "fursan-" + safeId;
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static String sanitizeName(String name) {
        if (name == null) return "هاتف Android";
        String safe = name.replaceAll("[\\r\\n\\u0000]", " ").trim();
        if (safe.isEmpty()) return "هاتف Android";
        return safe.length() > 40 ? safe.substring(0, 40) : safe;
    }
}
