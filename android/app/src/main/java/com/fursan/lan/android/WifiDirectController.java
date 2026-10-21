package com.fursan.lan.android;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/** Native Wi-Fi Direct group creation, discovery and joining. */
final class WifiDirectController implements WifiP2pManager.ChannelListener {
    interface Listener {
        void onStatus(String message);
        void onPeers(JSONArray peers);
        void onContactFound(String clientId, String name, String deviceAddress);
        void onGroupConnected(String ownerAddress, boolean isGroupOwner);
        void onGroupDisconnected();
        void onError(String message);
    }

    private final Activity activity;
    private final Listener listener;
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean registered;
    private static final String SERVICE_TYPE = "_fursanlan._tcp";
    private static final long DISCOVERY_INTERVAL_MS = 30_000L;
    private final android.os.Handler discoveryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Map<String, WifiDirectPeerIdentity> identitiesByAddress = new HashMap<>();
    private final Map<String, DiscoveredPeer> discoveredByClientId = new HashMap<>();
    private String localClientId;
    private String localName;
    private String pendingContactId;
    private long pendingContactSince;
    private WifiP2pDnsSdServiceInfo localServiceInfo;
    private WifiP2pDnsSdServiceRequest serviceRequest;
    private boolean serviceDiscoveryConfigured;
    private boolean serviceDiscoveryStarting;
    private boolean groupConnected;
    private final Runnable discoveryLoop = new Runnable() {
        @Override public void run() {
            if (!registered || localClientId == null) return;
            discoverServicesOnce();
            discoveryHandler.postDelayed(this, DISCOVERY_INTERVAL_MS);
        }
    };

    WifiDirectController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.manager = (WifiP2pManager) activity.getSystemService(Context.WIFI_P2P_SERVICE);
        initializeChannel();
    }

    private void initializeChannel() {
        if (manager != null) channel = manager.initialize(activity, activity.getMainLooper(), this);
    }

    void start() {
        if (manager == null || channel == null) {
            listener.onError("هذا الهاتف لا يوفّر واجهة Wi‑Fi Direct.");
            return;
        }
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    listener.onStatus(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            ? "Wi‑Fi Direct مفعّل؛ أنشئ مجموعة على هاتف أو ابحث عن هاتف أنشأها."
                            : "فعّل Wi‑Fi من إعدادات الهاتف لاستخدام Wi‑Fi Direct.");
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    requestConnectionInfo();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, filter);
        registered = true;
    }

    void setLocalIdentity(String clientId, String name) {
        if (clientId == null || !clientId.matches("^[A-Za-z0-9_-]{16,80}$")) {
            listener.onError("هوية فرسان لان غير صالحة؛ أعد فتح التطبيق.");
            return;
        }
        String safeName = name == null ? "هاتف Android" : name.replaceAll("[\\r\\n\\u0000]", " ").trim();
        if (safeName.isEmpty()) safeName = "هاتف Android";
        if (safeName.length() > 40) safeName = safeName.substring(0, 40);
        if (clientId.equals(localClientId) && safeName.equals(localName)) {
            startServiceDiscovery();
            return;
        }
        localClientId = clientId;
        localName = safeName;
        serviceDiscoveryConfigured = false;
        serviceDiscoveryStarting = false;
        localServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                WifiDirectPeerIdentity.serviceInstance(clientId), SERVICE_TYPE,
                WifiDirectPeerIdentity.createRecord(clientId, safeName));
        startServiceDiscovery();
    }

    void startServiceDiscovery() {
        if (!ensureAvailable() || localClientId == null || !hasPermission()) return;
        if (Build.VERSION.SDK_INT < 33 && !isLocationEnabled()) {
            listener.onStatus("فعّل خدمات الموقع مؤقتًا لاكتشاف Wi‑Fi Direct على هذا الإصدار؛ لا يقرأ فرسان لان إحداثياتك.");
            return;
        }
        if (groupConnected) return;
        if (serviceDiscoveryConfigured) {
            discoveryHandler.removeCallbacks(discoveryLoop);
            discoveryHandler.post(discoveryLoop);
            return;
        }
        if (serviceDiscoveryStarting) return;
        serviceDiscoveryStarting = true;
        try {
            manager.setDnsSdResponseListeners(channel,
                    (instanceName, registrationType, device) -> publishDiscoveredPeer(device),
                    (fullDomain, record, device) -> {
                        WifiDirectPeerIdentity identity = WifiDirectPeerIdentity.parse(record);
                        if (identity == null || identity.clientId.equals(localClientId)) return;
                        identitiesByAddress.put(device.deviceAddress, identity);
                        publishDiscoveredPeer(device);
                    });
            manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { registerLocalService(); }
            @Override public void onFailure(int reason) {
                serviceDiscoveryStarting = false;
                listener.onError("تعذر تجهيز إعلان فرسان لان عبر Wi‑Fi Direct: " + reasonText(reason));
            }
            });
        } catch (SecurityException error) {
            serviceDiscoveryStarting = false;
            listener.onError("لم يمنح Android إذن الأجهزة القريبة؛ اسمح به ثم أعد المحاولة.");
        }
    }

    private void registerLocalService() {
        if (!registered || localServiceInfo == null) return;
        manager.addLocalService(channel, localServiceInfo, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { configureServiceRequest(); }
            @Override public void onFailure(int reason) {
                serviceDiscoveryStarting = false;
                listener.onError("تعذر إعلان هوية الهاتف عبر Wi‑Fi Direct: " + reasonText(reason));
            }
        });
    }

    private void configureServiceRequest() {
        if (!registered) return;
        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
                manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {
                        serviceDiscoveryConfigured = true;
                        serviceDiscoveryStarting = false;
                        listener.onStatus("يبحث فرسان لان تلقائيًا عن الهواتف القريبة؛ اضغط جهة الاتصال لبدء الربط.");
                        discoveryHandler.removeCallbacks(discoveryLoop);
                        discoveryHandler.post(discoveryLoop);
                    }
                    @Override public void onFailure(int reason) {
                        serviceDiscoveryStarting = false;
                        listener.onError("تعذر إعداد اكتشاف نظير فرسان لان: " + reasonText(reason));
                    }
                });
            }
            @Override public void onFailure(int reason) {
                serviceDiscoveryStarting = false;
                listener.onError("تعذر إعادة تهيئة اكتشاف Wi‑Fi Direct: " + reasonText(reason));
            }
        });
    }

    private void discoverServicesOnce() {
        if (!registered || !serviceDiscoveryConfigured || !hasPermission()) return;
        try {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { }
                @Override public void onFailure(int reason) {
                    if (reason != WifiP2pManager.BUSY) {
                        listener.onStatus("تعذر البحث التلقائي عن النظير: " + reasonText(reason) + "؛ ستتم إعادة المحاولة.");
                    }
                }
            });
        } catch (SecurityException error) {
            listener.onError("لم يمنح Android إذن الأجهزة القريبة؛ اسمح به ثم أعد المحاولة.");
        }
    }

    private void publishDiscoveredPeer(WifiP2pDevice device) {
        if (device == null || device.deviceAddress == null) return;
        WifiDirectPeerIdentity identity = identitiesByAddress.get(device.deviceAddress);
        if (identity == null || identity.clientId.equals(localClientId)) return;
        DiscoveredPeer peer = new DiscoveredPeer(identity, device.deviceAddress, System.currentTimeMillis());
        discoveredByClientId.put(identity.clientId, peer);
        listener.onContactFound(identity.clientId, identity.name, device.deviceAddress);
        if (identity.clientId.equals(pendingContactId)
                && System.currentTimeMillis() - pendingContactSince <= 90_000L) {
            pendingContactId = null;
            pendingContactSince = 0L;
            listener.onStatus("عُثر على الهاتف المختار؛ جارٍ إنشاء اتصال Wi‑Fi Direct…");
            connect(device.deviceAddress);
        } else if (pendingContactId != null && System.currentTimeMillis() - pendingContactSince > 90_000L) {
            pendingContactId = null;
            pendingContactSince = 0L;
        }
    }

    void connectContact(String clientId) {
        if (clientId == null || !clientId.matches("^[A-Za-z0-9_-]{16,80}$")
                || clientId.equals(localClientId)) {
            listener.onError("تعذر تحديد هوية جهة الاتصال لربط Wi‑Fi Direct.");
            return;
        }
        DiscoveredPeer peer = discoveredByClientId.get(clientId);
        if (peer != null && System.currentTimeMillis() - peer.lastSeenAt < 90_000L) {
            connect(peer.deviceAddress);
            return;
        }
        pendingContactId = clientId;
        pendingContactSince = System.currentTimeMillis();
        listener.onStatus("جارٍ البحث عن جهة الاتصال عبر Wi‑Fi Direct؛ أبقِ فرسان لان مفتوحًا على الهاتف الآخر.");
        startServiceDiscovery();
    }

    private static final class DiscoveredPeer {
        final WifiDirectPeerIdentity identity;
        final String deviceAddress;
        final long lastSeenAt;
        DiscoveredPeer(WifiDirectPeerIdentity identity, String deviceAddress, long lastSeenAt) {
            this.identity = identity;
            this.deviceAddress = deviceAddress;
            this.lastSeenAt = lastSeenAt;
        }
    }

    void createGroup() {
        if (!ensureAvailable()) return;
        pauseServiceDiscovery();
        try {
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    listener.onStatus("أُنشئت مجموعة Wi‑Fi Direct؛ اطلب من الهاتف الآخر البحث ثم اختيار هذا الهاتف.");
                    requestConnectionInfo();
                }
                @Override public void onFailure(int reason) { listener.onError("تعذر إنشاء مجموعة Wi‑Fi Direct: " + reasonText(reason)); }
            });
        } catch (SecurityException error) {
            listener.onError("لم يمنح Android إذن أجهزة Wi‑Fi القريبة؛ اسمح به ثم أعد المحاولة.");
        }
    }

    void discoverPeers() {
        if (!ensureAvailable()) return;
        if (Build.VERSION.SDK_INT < 33 && !isLocationEnabled()) {
            listener.onError("فعّل خدمات الموقع مؤقتًا لاكتشاف Wi‑Fi Direct على هذا الإصدار من Android؛ لا يقرأ فرسان لان إحداثياتك.");
            return;
        }
        try {
            listener.onStatus("جارٍ البحث عن أجهزة Wi‑Fi Direct القريبة…");
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { listener.onStatus("بدأ البحث؛ ستظهر الأجهزة المتاحة هنا."); }
                @Override public void onFailure(int reason) { listener.onError("تعذر البحث عن أجهزة Wi‑Fi Direct: " + reasonText(reason)); }
            });
        } catch (SecurityException error) {
            listener.onError("لم يمنح Android إذن أجهزة Wi‑Fi القريبة؛ اسمح به ثم أعد المحاولة.");
        }
    }

    void connect(String deviceAddress) {
        if (!ensureAvailable()) return;
        if (deviceAddress == null || !deviceAddress.matches("(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
            listener.onError("عنوان جهاز Wi‑Fi Direct غير صالح؛ حدّث قائمة الأجهزة.");
            return;
        }
        pauseServiceDiscovery();
        try {
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = deviceAddress;
            listener.onStatus("أرسل Android طلب الانضمام؛ وافق على نافذة الاقتران التي تظهر على الهاتفين إن ظهرت.");
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { listener.onStatus("قُبل طلب Wi‑Fi Direct؛ جارٍ تأسيس المجموعة…"); }
                @Override public void onFailure(int reason) { listener.onError("تعذر الانضمام إلى مجموعة Wi‑Fi Direct: " + reasonText(reason)); }
            });
        } catch (SecurityException error) {
            listener.onError("لم يمنح Android إذن أجهزة Wi‑Fi القريبة؛ اسمح به ثم أعد المحاولة.");
        }
    }

    private boolean ensureAvailable() {
        if (manager == null || channel == null) {
            listener.onError("هذا الهاتف لا يوفّر واجهة Wi‑Fi Direct.");
            return false;
        }
        if (!registered) start();
        return registered;
    }

    private void requestPeers() {
        if (!registered || !hasPermission()) return;
        try {
            manager.requestPeers(channel, (WifiP2pDeviceList deviceList) -> {
                JSONArray result = new JSONArray();
                List<WifiP2pDevice> sorted = new ArrayList<>(deviceList.getDeviceList());
                sorted.sort((left, right) -> safeName(left).compareToIgnoreCase(safeName(right)));
                for (WifiP2pDevice device : sorted) {
                    JSONObject item = new JSONObject();
                    try {
                        item.put("name", safeName(device));
                        item.put("address", device.deviceAddress == null ? "" : device.deviceAddress);
                        item.put("status", statusText(device.status));
                        result.put(item);
                    } catch (Exception ignored) { }
                }
                listener.onPeers(result);
                if (result.length() == 0) listener.onStatus("لم يُعثر على أجهزة بعد؛ تأكد من تشغيل Wi‑Fi Direct على الهاتف الآخر ثم أعد البحث.");
            });
        } catch (SecurityException error) {
            listener.onError("Android منع قراءة قائمة أجهزة Wi‑Fi Direct؛ تحقق من الإذن.");
        }
    }

    private void requestConnectionInfo() {
        if (!registered || !hasPermission()) return;
        try {
            manager.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
                if (info == null || !info.groupFormed || info.groupOwnerAddress == null) {
                    groupConnected = false;
                    listener.onGroupDisconnected();
                    if (serviceDiscoveryConfigured && localClientId != null) {
                        discoveryHandler.removeCallbacks(discoveryLoop);
                        discoveryHandler.postDelayed(discoveryLoop, 5_000L);
                    }
                    return;
                }
                groupConnected = true;
                discoveryHandler.removeCallbacks(discoveryLoop);
                InetAddress owner = info.groupOwnerAddress;
                listener.onGroupConnected(owner.getHostAddress(), info.isGroupOwner);
            });
        } catch (SecurityException error) {
            listener.onError("Android منع قراءة حالة مجموعة Wi‑Fi Direct؛ تحقق من الإذن.");
        }
    }

    private boolean hasPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        LocationManager location = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (location == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return location.isLocationEnabled();
        try {
            return location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    || location.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) { return false; }
    }

    void pauseServiceDiscovery() {
        discoveryHandler.removeCallbacks(discoveryLoop);
        if (manager == null || channel == null || !registered) return;
        try {
            manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { }
                @Override public void onFailure(int reason) { }
            });
        } catch (SecurityException ignored) { }
    }

    private static String safeName(WifiP2pDevice device) {
        return device.deviceName == null || device.deviceName.trim().isEmpty() ? "جهاز Wi‑Fi Direct" : device.deviceName.trim();
    }

    private static String statusText(int status) {
        if (status == WifiP2pDevice.AVAILABLE) return "متاح";
        if (status == WifiP2pDevice.INVITED) return "طلب انضمام";
        if (status == WifiP2pDevice.CONNECTED) return "متصل";
        if (status == WifiP2pDevice.FAILED) return "فشل";
        if (status == WifiP2pDevice.UNAVAILABLE) return "غير متاح";
        return "حالة غير معروفة";
    }

    private static String reasonText(int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) return "ميزة P2P غير مدعومة في الجهاز";
        if (reason == WifiP2pManager.BUSY) return "النظام مشغول؛ انتظر قليلًا وأعد المحاولة";
        if (reason == WifiP2pManager.ERROR) return "خطأ من خدمة Wi‑Fi في النظام";
        return "رمز الخطأ " + reason;
    }

    @Override public void onChannelDisconnected() {
        channel = null;
        serviceDiscoveryConfigured = false;
        serviceDiscoveryStarting = false;
        initializeChannel();
        listener.onError("انقطعت قناة Wi‑Fi Direct؛ يحاول فرسان لان إعادة تهيئتها تلقائيًا.");
        if (channel != null && registered && localClientId != null) {
            discoveryHandler.postDelayed(this::startServiceDiscovery, 1_500L);
        }
    }

    void close() {
        pauseServiceDiscovery();
        if (registered && receiver != null) {
            try { activity.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        }
        if (manager != null && channel != null) {
            try { manager.clearServiceRequests(channel, null); } catch (Exception ignored) { }
            try { manager.clearLocalServices(channel, null); } catch (Exception ignored) { }
        }
        registered = false;
        receiver = null;
        serviceDiscoveryConfigured = false;
        groupConnected = false;
        identitiesByAddress.clear();
        discoveredByClientId.clear();
        pendingContactId = null;
        pendingContactSince = 0L;
    }
}
