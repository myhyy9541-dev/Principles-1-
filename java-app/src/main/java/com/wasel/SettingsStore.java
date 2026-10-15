package com.wasel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class SettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int DEFAULT_DISCOVERY_INTERVAL = 1800;
    public static final int MIN_DISCOVERY_INTERVAL = 500;
    public static final int MAX_DISCOVERY_INTERVAL = 15000;

    private SettingsStore() { }

    public static final class Adapter {
        public String id;
        public String name;
        public String address;
        public String netmask;
        public String mac;
        public String broadcast;
        public Adapter(String id, String name, String address, String netmask, String mac, String broadcast) {
            this.id = id; this.name = name; this.address = address; this.netmask = netmask; this.mac = mac; this.broadcast = broadcast;
        }
    }

    public static final class Settings {
        public String networkInterface = "auto";
        public int discoveryIntervalMs = DEFAULT_DISCOVERY_INTERVAL;
        public boolean autoConnect = true;
        public boolean openAtLogin = false;
        public String theme = "light";
    }

    public static List<Adapter> adapters() {
        List<Adapter> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return result;
            while (all.hasMoreElements()) {
                NetworkInterface network = all.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                byte[] hardware = network.getHardwareAddress();
                String mac = hardware == null ? "" : formatMac(hardware);
                var addresses = network.getInterfaceAddresses();
                for (var item : addresses) {
                    if (!(item.getAddress() instanceof Inet4Address ipv4) || ipv4.isLoopbackAddress() || ipv4.isLinkLocalAddress()) continue;
                    String address = ipv4.getHostAddress();
                    String mask = prefixToMask(item.getNetworkPrefixLength());
                    result.add(new Adapter(network.getName() + "@" + address, network.getDisplayName(), address, mask, mac, broadcastAddress(address, mask)));
                }
            }
        } catch (SocketException ignored) { }
        return result;
    }

    public static String broadcastAddress(String address, String netmask) {
        try {
            String[] ip = address.split("\\."), mask = netmask.split("\\.");
            if (ip.length != 4 || mask.length != 4) return "255.255.255.255";
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int host = Integer.parseInt(ip[i]);
                int net = Integer.parseInt(mask[i]);
                if (host < 0 || host > 255 || net < 0 || net > 255) return "255.255.255.255";
                if (i > 0) result.append('.');
                result.append(host | (255 ^ net));
            }
            return result.toString();
        } catch (RuntimeException ex) { return "255.255.255.255"; }
    }

    private static String prefixToMask(short prefix) {
        int bits = Math.max(0, Math.min(32, prefix));
        long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
        return ((mask >>> 24) & 255) + "." + ((mask >>> 16) & 255) + "." + ((mask >>> 8) & 255) + "." + (mask & 255);
    }

    private static String formatMac(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) { if (!out.isEmpty()) out.append(':'); out.append(String.format("%02X", value & 0xff)); }
        return out.toString();
    }

    public static Settings normalize(Settings candidate, List<Adapter> adapters) {
        Settings value = candidate == null ? new Settings() : candidate;
        Settings clean = new Settings();
        boolean known = "auto".equals(value.networkInterface) || adapters.stream().anyMatch(a -> a.id.equals(value.networkInterface));
        clean.networkInterface = known ? value.networkInterface : "auto";
        clean.discoveryIntervalMs = value.discoveryIntervalMs >= MIN_DISCOVERY_INTERVAL && value.discoveryIntervalMs <= MAX_DISCOVERY_INTERVAL
                ? value.discoveryIntervalMs : DEFAULT_DISCOVERY_INTERVAL;
        clean.autoConnect = value.autoConnect;
        clean.openAtLogin = value.openAtLogin;
        clean.theme = List.of("light", "dark", "system").contains(value.theme) ? value.theme : "light";
        return clean;
    }

    public static Settings load(Path file, List<Adapter> adapters) {
        try { return normalize(GSON.fromJson(Files.readString(file), Settings.class), adapters); }
        catch (Exception ignored) { return new Settings(); }
    }

    public static Settings save(Path file, Settings settings, List<Adapter> adapters) throws IOException {
        Settings clean = normalize(settings, adapters);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(clean));
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        return clean;
    }

    public static JsonObject toJson(Settings settings) { return GSON.toJsonTree(settings).getAsJsonObject(); }
}
