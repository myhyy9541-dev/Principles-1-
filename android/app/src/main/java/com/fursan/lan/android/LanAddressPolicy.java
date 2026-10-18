package com.fursan.lan.android;

final class LanAddressPolicy {
    private LanAddressPolicy() {}

    static boolean isPrivateLanAddress(String host) {
        if (host == null) return false;
        if (host.equals("localhost") || host.startsWith("127.")) return true;
        String[] octets = host.split("\\.");
        if (octets.length != 4) return false;
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
            }
            return first == 10 || (first == 192 && second == 168) || (first == 172 && second >= 16 && second <= 31);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
