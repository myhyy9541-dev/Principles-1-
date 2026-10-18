package com.fursan.lan.android;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

final class PeerDiscovery implements AutoCloseable {
    static final int DISCOVERY_PORT = 43820;
    static final int PEER_PORT = 43821;
    static final String PROTOCOL = "fursan-p2p-v1";
    private static final String TAG = "FursanPeerDiscovery";
    private final Listener listener;
    private volatile boolean running;
    private DatagramSocket socket;
    private Thread worker;

    interface Listener {
        void onPeer(PeerAdvertisement peer);
        void onDiscoveryError(String message);
    }

    PeerDiscovery(Listener listener) {
        this.listener = listener;
    }

    static boolean isCompatible(String protocol, int port) {
        return PROTOCOL.equals(protocol) && port == PEER_PORT;
    }

    static PeerAdvertisement parseAdvertisement(byte[] bytes, int length, String address) {
        if (bytes == null || length <= 0 || length > bytes.length || address == null || address.isEmpty()) return null;
        try {
            JSONObject packet = new JSONObject(new String(bytes, 0, length, StandardCharsets.UTF_8));
            String protocol = packet.optString("protocol", "");
            int port = packet.optInt("port", -1);
            if (!isCompatible(protocol, port)) return null;
            String name = packet.optString("name", "نظير Windows").replaceAll("[\\r\\n\\u0000]", " ").trim();
            if (name.length() > 80) name = name.substring(0, 80);
            if (name.isEmpty()) name = "نظير Windows";
            return new PeerAdvertisement(address, port, name);
        } catch (Exception error) {
            Log.d(TAG, "Ignoring malformed peer advertisement", error);
            return null;
        }
    }

    synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::receiveLoop, "fursan-wifi-peer-discovery");
        worker.setDaemon(true);
        worker.start();
    }

    private void receiveLoop() {
        try (DatagramSocket activeSocket = new DatagramSocket(null)) {
            socket = activeSocket;
            activeSocket.setReuseAddress(true);
            activeSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
            activeSocket.setBroadcast(true);
            activeSocket.setSoTimeout(1200);
            byte[] buffer = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    activeSocket.receive(packet);
                    PeerAdvertisement peer = parseAdvertisement(packet.getData(), packet.getLength(), packet.getAddress().getHostAddress());
                    if (peer != null) listener.onPeer(peer);
                } catch (SocketTimeoutException ignored) {
                    // A timeout lets stop() be observed without busy waiting.
                }
            }
        } catch (SocketException error) {
            if (running) {
                Log.w(TAG, "LAN discovery socket failed", error);
                listener.onDiscoveryError("تعذر الاستماع لاكتشاف Wi‑Fi: " + error.getMessage());
            }
        } catch (Exception error) {
            if (running) {
                Log.w(TAG, "LAN discovery failed", error);
                listener.onDiscoveryError("تعذر اكتشاف أجهزة الشبكة: " + error.getMessage());
            }
        } finally {
            socket = null;
            running = false;
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (socket != null) socket.close();
        if (worker != null) worker.interrupt();
        worker = null;
    }

    static final class PeerAdvertisement {
        final String address;
        final int port;
        final String name;

        PeerAdvertisement(String address, int port, String name) {
            this.address = address;
            this.port = port;
            this.name = name;
        }
    }
}
