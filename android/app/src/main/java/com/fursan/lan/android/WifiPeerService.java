package com.fursan.lan.android;

import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.util.Map;
import java.util.UUID;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct Android-to-Android Wi-Fi transport. No relay or central server is used. */
final class WifiPeerService implements AutoCloseable {
    static final int PEER_PORT = 43821;
    static final int DISCOVERY_PORT = 43820;
    static final String PROTOCOL = "fursan-android-frame-v1";
    private static final String TAG = "FursanWifiPeer";
    private static final int MAX_PEERS = 8;
    private static final int MAX_QUEUED_FRAMES_PER_PEER = 16;

    interface Listener {
        void onWifiPeerConnected(String address);
        void onWifiPeerDisconnected(String address);
        void onWifiFrame(String address, JSONObject frame);
        void onWifiError(String address, String message);
    }

    private final Listener listener;
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> connecting = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor ioExecutor = new ThreadPoolExecutor(
            0, MAX_PEERS * 3, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_PEERS * 2), runnable -> {
                Thread thread = new Thread(runnable, "fursan-wifi-peer-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService beaconExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fursan-wifi-beacon");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private DatagramSocket beaconSocket;

    WifiPeerService(Listener listener) {
        this.listener = listener;
    }

    String getInstanceId() { return instanceId; }

    static boolean shouldInitiate(String localInstanceId, String remoteInstanceId) {
        return localInstanceId != null && remoteInstanceId != null
                && localInstanceId.compareToIgnoreCase(remoteInstanceId) < 0;
    }

    synchronized void start() throws IOException {
        if (running) return;
        ServerSocket server = new ServerSocket();
        DatagramSocket beacon = null;
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PEER_PORT));
            beacon = new DatagramSocket();
            beacon.setBroadcast(true);
        } catch (IOException error) {
            try { server.close(); } catch (IOException ignored) { }
            if (beacon != null) beacon.close();
            throw error;
        }
        serverSocket = server;
        beaconSocket = beacon;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "fursan-wifi-peer-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        beaconExecutor.scheduleAtFixedRate(this::announce, 0, 2, TimeUnit.SECONDS);
    }

    /** A deterministic UUID ordering selects exactly one dialer for each pair. */
    boolean connect(String address, int port, String remoteInstanceId) throws IOException {
        if (!running) throw new IOException("خدمة Wi-Fi المباشرة غير مفعلة.");
        if (port != PEER_PORT || !LanAddressPolicy.isPrivateLanAddress(address)) {
            throw new IOException("عنوان النظير أو منفذ Wi-Fi غير صالح.");
        }
        if (remoteInstanceId == null || !remoteInstanceId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IOException("هوية إعلان Wi-Fi غير صالحة.");
        }
        if (!shouldInitiate(instanceId, remoteInstanceId)) return false;
        if (peers.containsKey(address) || connecting.putIfAbsent(address, Boolean.TRUE) != null) return true;
        if (peers.size() + connecting.size() > MAX_PEERS) {
            connecting.remove(address);
            throw new IOException("بلغت الحد الأقصى لأجهزة Wi-Fi المتصلة.");
        }
        try {
            ioExecutor.execute(() -> {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(address, port), 8000);
                    connecting.remove(address);
                    attach(socket);
                    socket = null;
                } catch (Exception error) {
                    closeQuietly(socket);
                    listener.onWifiError(address, "تعذر الاتصال بنظير Wi-Fi مباشرة: " + safeMessage(error));
                } finally {
                    connecting.remove(address);
                }
            });
        } catch (RejectedExecutionException error) {
            connecting.remove(address);
            throw new IOException("خدمة Wi-Fi مشغولة؛ حاول لاحقاً.", error);
        }
        return true;
    }

    boolean connectManually(String address) throws IOException {
        if (!running) throw new IOException("خدمة Wi-Fi المباشرة غير مفعلة.");
        if (!LanAddressPolicy.isPrivateLanAddress(address)) throw new IOException("أدخل عنوان IPv4 محليًا مثل 192.168.49.2.");
        if (isLocalAddress(address)) throw new IOException("هذا عنوان الهاتف نفسه؛ أدخل عنوان الهاتف الآخر.");
        return dial(address, PEER_PORT);
    }

    private boolean dial(String address, int port) throws IOException {
        if (peers.containsKey(address) || connecting.putIfAbsent(address, Boolean.TRUE) != null) return true;
        if (peers.size() + connecting.size() > MAX_PEERS) {
            connecting.remove(address);
            throw new IOException("بلغت الحد الأقصى لأجهزة Wi-Fi المتصلة.");
        }
        try {
            ioExecutor.execute(() -> {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(address, port), 8000);
                    connecting.remove(address);
                    attach(socket);
                    socket = null;
                } catch (Exception error) {
                    closeQuietly(socket);
                    listener.onWifiError(address, "تعذر الاتصال بـ" + address + ":" + port + " مباشرةً: " + safeMessage(error));
                } finally { connecting.remove(address); }
            });
        } catch (RejectedExecutionException error) {
            connecting.remove(address);
            throw new IOException("خدمة Wi-Fi مشغولة؛ حاول لاحقاً.", error);
        }
        return true;
    }

    String getLocalAddresses() {
        StringBuilder result = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "";
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    String host = address.getHostAddress();
                    if (!LanAddressPolicy.isPrivateLanAddress(host) || result.toString().contains(host)) continue;
                    if (result.length() > 0) result.append("، ");
                    result.append(host);
                }
            }
        } catch (Exception error) { Log.w(TAG, "Could not enumerate local interfaces", error); }
        return result.toString();
    }

    private boolean isLocalAddress(String candidate) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            while (interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) if (candidate.equals(addresses.nextElement().getHostAddress())) return true;
            }
        } catch (Exception error) { Log.d(TAG, "Could not compare local address", error); }
        return false;
    }

    boolean send(String address, JSONObject frame) {
        PeerConnection peer = peers.get(address);
        return peer != null && peer.enqueue(frame);
    }

    void disconnect(String address) {
        PeerConnection peer = peers.get(address);
        if (peer != null) peer.close();
    }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                ServerSocket server = serverSocket;
                if (server == null) return;
                socket = server.accept();
                if (!running) { closeQuietly(socket); return; }
                String address = socket.getInetAddress().getHostAddress();
                if (!LanAddressPolicy.isPrivateLanAddress(address)) {
                    closeQuietly(socket);
                    continue;
                }
                attach(socket);
                socket = null;
            } catch (SocketException error) {
                if (running) listener.onWifiError(null, "توقف استقبال Wi-Fi: " + safeMessage(error));
                closeQuietly(socket);
                return;
            } catch (IOException error) {
                closeQuietly(socket);
                if (running) listener.onWifiError(null, "تعذر استقبال اتصال Wi-Fi: " + safeMessage(error));
            }
        }
    }

    private synchronized void attach(Socket socket) {
        String address = socket.getInetAddress().getHostAddress();
        if (address == null || peers.size() + connecting.size() >= MAX_PEERS) {
            closeQuietly(socket);
            return;
        }
        PeerConnection connection = new PeerConnection(address, socket);
        PeerConnection existing = peers.putIfAbsent(address, connection);
        if (existing != null) { closeQuietly(socket); return; }
        listener.onWifiPeerConnected(address);
        try {
            ioExecutor.execute(connection::readLoop);
            ioExecutor.execute(connection::writeLoop);
        } catch (RejectedExecutionException error) {
            connection.close();
            listener.onWifiError(address, "لا تتوفر موارد كافية لاتصال Wi-Fi إضافي.");
        }
    }

    private void announce() {
        if (!running || beaconSocket == null || beaconSocket.isClosed()) return;
        try {
            JSONObject packet = new JSONObject()
                    .put("protocol", PROTOCOL)
                    .put("transport", "wifi-frame-v1")
                    .put("port", PEER_PORT)
                    .put("name", safeDeviceName())
                    .put("instanceId", instanceId);
            byte[] bytes = packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (InetAddress target : broadcastAddresses()) {
                try { beaconSocket.send(new DatagramPacket(bytes, bytes.length, target, DISCOVERY_PORT)); }
                catch (IOException error) { Log.d(TAG, "Could not send beacon to " + target.getHostAddress(), error); }
            }
        } catch (Exception error) {
            if (running) Log.d(TAG, "Wi-Fi announcement failed", error);
        }
    }

    private java.util.List<InetAddress> broadcastAddresses() throws Exception {
        java.util.LinkedHashMap<String, InetAddress> results = new java.util.LinkedHashMap<>();
        results.put("255.255.255.255", InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress binding : network.getInterfaceAddresses()) {
                InetAddress broadcast = binding.getBroadcastAddress();
                if (broadcast != null) results.put(broadcast.getHostAddress(), broadcast);
            }
        }
        return new java.util.ArrayList<>(results.values());
    }

    private String safeDeviceName() {
        String name = Build.MODEL == null ? "Android" : Build.MODEL.replaceAll("[\\r\\n\\u0000]", " ").trim();
        if (name.isEmpty()) name = "Android";
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        running = false;
        ServerSocket server = serverSocket;
        serverSocket = null;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        DatagramSocket beacon = beaconSocket;
        beaconSocket = null;
        if (beacon != null) beacon.close();
        beaconExecutor.shutdownNow();
        for (PeerConnection peer : peers.values()) peer.close();
        peers.clear();
        connecting.clear();
        if (acceptThread != null) acceptThread.interrupt();
        acceptThread = null;
        ioExecutor.shutdownNow();
    }

    private final class PeerConnection {
        private final String address;
        private final Socket socket;
        private final ArrayBlockingQueue<JSONObject> outgoing = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES_PER_PEER);
        private final AtomicBoolean open = new AtomicBoolean(true);

        PeerConnection(String address, Socket socket) { this.address = address; this.socket = socket; }

        boolean enqueue(JSONObject frame) { return open.get() && frame != null && outgoing.offer(frame); }

        void readLoop() {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8192))) {
                while (open.get()) listener.onWifiFrame(address, BluetoothFrameCodec.readFrame(input));
            } catch (IOException error) {
                if (open.get() && running) Log.d(TAG, "Wi-Fi peer read ended: " + safeMessage(error));
            } finally { close(); }
        }

        void writeLoop() {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8192))) {
                while (open.get()) BluetoothFrameCodec.writeFrame(output, outgoing.take());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                if (open.get() && running) Log.d(TAG, "Wi-Fi peer write ended: " + safeMessage(error));
            } finally { close(); }
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            peers.remove(address, this);
            closeQuietly(socket);
            listener.onWifiPeerDisconnected(address);
        }
    }
}
