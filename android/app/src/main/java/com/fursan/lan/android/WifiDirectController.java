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
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native Wi-Fi Direct group creation, discovery and joining. */
final class WifiDirectController implements WifiP2pManager.ChannelListener {
    interface Listener {
        void onStatus(String message);
        void onPeers(JSONArray peers);
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

    void createGroup() {
        if (!ensureAvailable()) return;
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
                    listener.onGroupDisconnected();
                    return;
                }
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
        listener.onError("انقطعت قناة Wi‑Fi Direct؛ أعد فتح التطبيق لإعادة تهيئتها.");
    }

    void close() {
        if (registered && receiver != null) {
            try { activity.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        }
        registered = false;
        receiver = null;
    }
}
