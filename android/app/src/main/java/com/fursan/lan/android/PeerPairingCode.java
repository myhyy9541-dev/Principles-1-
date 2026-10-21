package com.fursan.lan.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validates the small, offline QR payload used to exchange a local peer endpoint. */
final class PeerPairingCode {
    static final String PROTOCOL = "fursan-lan-peer";
    static final int VERSION = 1;

    private PeerPairingCode() { }

    static JSONObject create(String address, String instanceId) throws JSONException {
        if (!isPrivateIpv4(address)) throw new JSONException("لا يوجد عنوان Wi-Fi محلي صالح لإنشاء الرمز.");
        if (instanceId == null || !instanceId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new JSONException("تعذر إنشاء هوية نظير صالحة.");
        }
        return new JSONObject()
                .put("protocol", PROTOCOL)
                .put("version", VERSION)
                .put("address", address)
                .put("port", WifiPeerService.PEER_PORT)
                .put("instanceId", instanceId);
    }

    static String parseAddress(String raw) throws JSONException {
        if (raw == null || raw.length() > 2048) throw new JSONException("رمز الاقتران فارغ أو طويل بشكل غير صالح.");
        JSONObject payload = new JSONObject(raw);
        if (!PROTOCOL.equals(payload.optString("protocol")) || payload.optInt("version", -1) != VERSION) {
            throw new JSONException("هذا الرمز ليس رمز اقتران صالحًا لفرسان لان.");
        }
        if (payload.optInt("port", -1) != WifiPeerService.PEER_PORT) throw new JSONException("منفذ النظير في الرمز غير مدعوم.");
        String instanceId = payload.optString("instanceId", "");
        if (!instanceId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new JSONException("هوية النظير في الرمز غير صالحة.");
        }
        String address = payload.optString("address", "").trim();
        if (!isPrivateIpv4(address)) {
            throw new JSONException("الرمز لا يحتوي عنوان IPv4 محليًا صالحًا.");
        }
        return address;
    }

    private static boolean isPrivateIpv4(String address) {
        return address != null && address.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
                && LanAddressPolicy.isPrivateLanAddress(address);
    }
}
