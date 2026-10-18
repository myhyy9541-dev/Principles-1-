package com.wasel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class LanDiscovery implements AutoCloseable {
    public static final int DISCOVERY_PORT = 43820;
    public static final int SOCKET_PORT = 43821;
    public static final int FILE_PORT = 43822;
    public static final int PEER_PORT = 43823;
    public static final String PROTOCOL = "wasel-lan-java-v1";
    public static final String PEER_PROTOCOL = "wasel-lan-p2p-v1";
    private static final Gson GSON = new Gson();
    private final Consumer<JsonObject> roomFound;
    private final Consumer<JsonObject> peerFound;
    private final String hostname;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "wasel-discovery-sender"); t.setDaemon(true); return t; });
    private volatile SettingsStore.Settings settings;
    private volatile boolean hosting;
    private volatile boolean peerEnabled;
    private volatile String peerClientId = "";
    private volatile String peerName = "";
    private volatile DatagramSocket socket;
    private volatile Thread listenerThread;
    private volatile ScheduledFuture<?> announcementTask;
    private volatile String broadcastAddress = "255.255.255.255";
    private volatile String bindAddress = "0.0.0.0";

    public LanDiscovery(String hostname, SettingsStore.Settings initial, Consumer<JsonObject> roomFound, Consumer<JsonObject> peerFound) {
        this.hostname = hostname; this.settings = initial; this.roomFound = roomFound; this.peerFound = peerFound;
        startSocket(); restartAnnouncements();
    }

    public synchronized void updateSettings(SettingsStore.Settings next) {
        this.settings = next; startSocket(); restartAnnouncements();
    }
    public void setHosting(boolean active) { hosting = active; if (active) sendAnnouncement(); }
    public void setPeerProfile(String clientId, String name, boolean enabled) {
        peerClientId = clientId == null ? "" : clientId;
        peerName = name == null ? "" : name;
        peerEnabled = enabled;
        if (enabled) sendAnnouncement();
    }

    private synchronized void startSocket() {
        if (closed.get()) return;
        if (socket != null) socket.close();
        List<SettingsStore.Adapter> adapters = SettingsStore.adapters();
        SettingsStore.Adapter selected = adapters.stream().filter(a -> a.id.equals(settings.networkInterface)).findFirst().orElse(null);
        bindAddress = selected == null ? "0.0.0.0" : selected.address;
        broadcastAddress = selected == null ? "255.255.255.255" : selected.broadcast;
        try {
            DatagramSocket next = new DatagramSocket(null); next.setReuseAddress(true); next.setBroadcast(true);
            next.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), DISCOVERY_PORT));
            socket = next;
            listenerThread = new Thread(() -> listen(next), "wasel-discovery-listener"); listenerThread.setDaemon(true); listenerThread.start();
        } catch (Exception ex) {
            socket = null;
            System.err.println("Wasel UDP discovery could not bind " + bindAddress + ": " + ex.getMessage());
        }
    }

    private synchronized void restartAnnouncements() {
        if (closed.get()) return;
        if (announcementTask != null) announcementTask.cancel(false);
        announcementTask = scheduler.scheduleWithFixedDelay(this::sendAnnouncement, 0,
                Math.max(SettingsStore.MIN_DISCOVERY_INTERVAL, settings.discoveryIntervalMs), TimeUnit.MILLISECONDS);
    }

    private void listen(DatagramSocket source) {
        byte[] buffer = new byte[2048];
        while (!closed.get() && !source.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length); source.receive(packet);
                JsonObject message = GSON.fromJson(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8), JsonObject.class);
                if (message == null) continue;
                String protocol = text(message, "protocol", "");
                String address = packet.getAddress().getHostAddress();
                if (PROTOCOL.equals(protocol) && message.has("port") && message.get("port").getAsInt() == SOCKET_PORT) {
                    if (hosting && SettingsStore.adapters().stream().anyMatch(a -> a.address.equals(address))) continue;
                    JsonObject room = new JsonObject(); room.addProperty("address", address); room.addProperty("port", SOCKET_PORT); room.addProperty("filePort", FILE_PORT);
                    room.addProperty("name", text(message, "name", "مضيف محلي")); room.addProperty("hostname", text(message, "hostname", ""));
                    roomFound.accept(room);
                } else if (PEER_PROTOCOL.equals(protocol) && message.has("peerPort") && message.get("peerPort").getAsInt() == PEER_PORT) {
                    String remoteId = text(message, "clientId", "");
                    if (remoteId.isBlank() || remoteId.equals(peerClientId) || SettingsStore.adapters().stream().anyMatch(a -> a.address.equals(address))) continue;
                    JsonObject peer = new JsonObject(); peer.addProperty("address", address); peer.addProperty("port", PEER_PORT); peer.addProperty("filePort", FILE_PORT);
                    peer.addProperty("clientId", remoteId); peer.addProperty("name", text(message, "name", "مستخدم")); peer.addProperty("hostname", text(message, "hostname", ""));
                    peerFound.accept(peer);
                }
            } catch (Exception ex) { if (!source.isClosed()) System.err.println("Wasel discovery receive: " + ex.getMessage()); }
        }
    }

    private void sendAnnouncement() {
        DatagramSocket active = socket;
        if (closed.get() || active == null || active.isClosed()) return;
        try {
            if (hosting) {
                JsonObject room = new JsonObject(); room.addProperty("protocol", PROTOCOL); room.addProperty("port", SOCKET_PORT);
                room.addProperty("filePort", FILE_PORT); room.addProperty("name", hostname); room.addProperty("hostname", hostname);
                send(active, room);
            }
            if (peerEnabled && peerClientId.matches("[A-Za-z0-9_-]{16,80}")) {
                JsonObject peer = new JsonObject(); peer.addProperty("protocol", PEER_PROTOCOL); peer.addProperty("peerPort", PEER_PORT);
                peer.addProperty("filePort", FILE_PORT); peer.addProperty("clientId", peerClientId); peer.addProperty("name", peerName.isBlank() ? hostname : peerName); peer.addProperty("hostname", hostname);
                send(active, peer);
            }
        } catch (Exception ex) { System.err.println("Wasel discovery send: " + ex.getMessage()); }
    }

    private void send(DatagramSocket active, JsonObject message) throws Exception {
        byte[] data = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        active.send(new DatagramPacket(data, data.length, InetAddress.getByName(broadcastAddress), DISCOVERY_PORT));
    }

    public static String text(JsonObject object, String key, String fallback) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
        catch (RuntimeException ex) { return fallback; }
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (announcementTask != null) announcementTask.cancel(true);
        scheduler.shutdownNow(); if (socket != null) socket.close();
        if (listenerThread != null) listenerThread.interrupt();
    }
}
