package com.fursan.lan.android;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

final class BluetoothPeerService implements AutoCloseable {
    static final int MAX_PEERS = 4;
    static final int MAX_QUEUED_FRAMES_PER_PEER = 16;
    static final String SERVICE_NAME = "Fursan LAN";
    static final java.util.UUID SERVICE_UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String TAG = "FursanBluetooth";
    private static final Pattern ADDRESS = Pattern.compile("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");

    interface Listener {
        void onPeerConnected(String address, String name);
        void onPeerDisconnected(String address);
        void onFrame(String address, JSONObject frame);
        void onError(String message);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> connecting = new ConcurrentHashMap<>();
    private final Map<String, BluetoothSocket> connectionAttempts = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor ioExecutor = new ThreadPoolExecutor(
            0, MAX_PEERS * 3, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_PEERS * 2), runnable -> {
                Thread thread = new Thread(runnable, "fursan-bluetooth-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService connectTimeouts = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fursan-bluetooth-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private BluetoothServerSocket serverSocket;
    private Thread acceptThread;

    BluetoothPeerService(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    synchronized void start() throws IOException {
        if (running) return;
        if (adapter == null) throw new IOException("الجهاز لا يحتوي على Bluetooth.");
        if (!adapter.isEnabled()) throw new IOException("فعّل Bluetooth من إعدادات الهاتف ثم أعد المحاولة.");
        serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "fursan-bluetooth-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void connect(String address) throws IOException {
        if (!running) throw new IOException("مستمع Bluetooth غير جاهز؛ افتح لوحة Bluetooth على الجهازين.");
        if (adapter == null || !adapter.isEnabled()) throw new IOException("فعّل Bluetooth أولاً.");
        if (!ADDRESS.matcher(address == null ? "" : address).matches() || !BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IOException("عنوان Bluetooth غير صالح.");
        }
        if (peers.containsKey(address) || connecting.putIfAbsent(address, Boolean.TRUE) != null) return;
        if (peers.size() + connecting.size() > MAX_PEERS) {
            connecting.remove(address);
            throw new IOException("بلغت الحد الأقصى لأجهزة Bluetooth المتصلة.");
        }
        try {
            ioExecutor.execute(() -> {
                BluetoothSocket socket = null;
                ScheduledFuture<?> timeout = null;
                AtomicBoolean timedOut = new AtomicBoolean(false);
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                    connectionAttempts.put(address, socket);
                    BluetoothSocket attemptSocket = socket;
                    timeout = connectTimeouts.schedule(() -> {
                        timedOut.set(true);
                        closeQuietly(attemptSocket);
                    }, 25, TimeUnit.SECONDS);
                    try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { }
                    socket.connect();
                    timeout.cancel(false);
                    connecting.remove(address);
                    attach(socket, device);
                    socket = null;
                } catch (Exception error) {
                    if (timeout != null) timeout.cancel(false);
                    closeQuietly(socket);
                    listener.onError(timedOut.get()
                            ? "انتهت مهلة Bluetooth بعد 25 ثانية؛ افتح مستمع Bluetooth على الجهاز الآخر ثم أعد المحاولة."
                            : "تعذر الاتصال بجهاز Bluetooth: " + safeMessage(error));
                } finally {
                    connecting.remove(address);
                    connectionAttempts.remove(address);
                }
            });
        } catch (RejectedExecutionException error) {
            connecting.remove(address);
            throw new IOException("خدمة Bluetooth مشغولة؛ حاول لاحقاً.", error);
        }
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
            BluetoothServerSocket server = serverSocket;
            if (server == null) return;
            BluetoothSocket socket = null;
            try {
                socket = server.accept();
                if (!running) {
                    closeQuietly(socket);
                    return;
                }
                BluetoothDevice device = socket.getRemoteDevice();
                attach(socket, device);
                socket = null;
            } catch (IOException error) {
                closeQuietly(socket);
                if (running) listener.onError("توقف استقبال Bluetooth: " + safeMessage(error));
                return;
            } catch (SecurityException error) {
                closeQuietly(socket);
                if (running) listener.onError("إذن Bluetooth غير متاح؛ أغلق التطبيق وافتحه بعد منح الإذن.");
                return;
            }
        }
    }

    private synchronized void attach(BluetoothSocket socket, BluetoothDevice device) {
        String address = device.getAddress();
        String name;
        try { name = device.getName(); } catch (SecurityException denied) { name = null; }
        if (address == null || peers.size() + connecting.size() >= MAX_PEERS) {
            closeQuietly(socket);
            listener.onError("لا يمكن قبول اتصال Bluetooth إضافي.");
            return;
        }
        PeerConnection connection = new PeerConnection(address, socket);
        PeerConnection existing = peers.putIfAbsent(address, connection);
        if (existing != null) {
            closeQuietly(socket);
            return;
        }
        listener.onPeerConnected(address, sanitizeName(name));
        try {
            ioExecutor.execute(connection::readLoop);
            ioExecutor.execute(connection::writeLoop);
        } catch (RejectedExecutionException error) {
            connection.close();
            listener.onError("لا تتوفر موارد كافية لفتح اتصال Bluetooth إضافي.");
        }
    }

    private static String sanitizeName(String value) {
        if (value == null) return "جهاز Bluetooth";
        String safe = value.replaceAll("[\\r\\n\\u0000]", " ").trim();
        if (safe.isEmpty()) return "جهاز Bluetooth";
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public synchronized void close() {
        running = false;
        BluetoothServerSocket server = serverSocket;
        serverSocket = null;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        for (PeerConnection peer : peers.values()) peer.close();
        for (BluetoothSocket attempt : connectionAttempts.values()) closeQuietly(attempt);
        peers.clear();
        connecting.clear();
        connectionAttempts.clear();
        if (acceptThread != null) acceptThread.interrupt();
        acceptThread = null;
        connectTimeouts.shutdownNow();
        ioExecutor.shutdownNow();
    }

    private final class PeerConnection {
        private final String address;
        private final BluetoothSocket socket;
        private final ArrayBlockingQueue<JSONObject> outgoing = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES_PER_PEER);
        private final AtomicBoolean open = new AtomicBoolean(true);

        PeerConnection(String address, BluetoothSocket socket) {
            this.address = address;
            this.socket = socket;
        }

        boolean enqueue(JSONObject frame) {
            if (!open.get() || frame == null || !outgoing.offer(frame)) return false;
            return true;
        }

        void readLoop() {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8192))) {
                while (open.get()) listener.onFrame(address, BluetoothFrameCodec.readFrame(input));
            } catch (IOException error) {
                if (open.get() && running) Log.d(TAG, "Bluetooth read ended: " + safeMessage(error));
            } finally { close(); }
        }

        void writeLoop() {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8192))) {
                while (open.get()) BluetoothFrameCodec.writeFrame(output, outgoing.take());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                if (open.get() && running) Log.d(TAG, "Bluetooth write ended: " + safeMessage(error));
            } finally { close(); }
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            peers.remove(address, this);
            closeQuietly(socket);
            listener.onPeerDisconnected(address);
        }
    }
}
