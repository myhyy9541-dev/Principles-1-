package com.fursan.lan.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.SoftApConfiguration;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements PeerDiscovery.Listener, BluetoothPeerService.Listener {
    private static final String TAG = "FursanLanAndroid";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final int FILE_REQUEST = 7101;
    private static final int MEDIA_REQUEST = 7102;
    private static final int BLUETOOTH_REQUEST = 7103;
    private static final int WIFI_DIRECT_PERMISSION_REQUEST = 7104;
    private static final int QR_SIZE = 480;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> knownPeers = ConcurrentHashMap.newKeySet();
    private WebView webView;
    private PeerDiscovery discovery;
    private volatile WifiPeerService wifiPeerService;
    private volatile WifiDirectController wifiDirectController;
    private volatile WifiManager.LocalOnlyHotspotReservation localHotspotReservation;
    private volatile WifiManager.LocalOnlyHotspotCallback localHotspotCallback;
    private volatile boolean localHotspotStarting;
    private volatile String localHotspotSsid = "";
    private volatile String localHotspotPassphrase = "";
    private volatile BluetoothPeerService bluetoothService;
    private WifiManager.MulticastLock multicastLock;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingMediaRequest;
    private String[] pendingWebResources = new String[0];
    private boolean pageReady;
    private String pendingWifiDirectAction;
    private String pendingWifiDirectPeer;
    private volatile String localWifiClientId;
    private volatile String localWifiDisplayName;
    private volatile String connectedWifiDirectOwner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(0xFF102D32);
        window.setNavigationBarColor(0xFF102D32);
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);
        webView.addJavascriptInterface(new BluetoothJsBridge(), "FursanBluetooth");
        webView.addJavascriptInterface(new WifiJsBridge(), "FursanWifiPeer");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !ASSET_HOST.equals(uri.getHost()) && !isKnownPeerFileUrl(uri);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pageReady) return;
                pageReady = true;
                webView.evaluateJavascript("document.getElementById('connectionText').textContent='جارٍ اكتشاف أجهزة فرسان لان عبر Wi‑Fi…';", null);
                startDiscovery();
                if (hasBluetoothPermission()) {
                    try {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null && adapter.isEnabled()) startBluetoothService();
                    } catch (SecurityException ignored) { }
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(intent, FILE_REQUEST);
                    return true;
                } catch (Exception error) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "تعذر فتح منتقي الملفات.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("حسنًا", (dialog, which) -> result.confirm()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("موافق", (dialog, which) -> result.confirm()).setNegativeButton("إلغاء", (dialog, which) -> result.cancel()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) {
                EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this).setTitle(message).setView(input)
                        .setPositiveButton("موافق", (dialog, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("إلغاء", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> downloadFile(url, userAgent, contentDisposition, mimeType));
        webView.loadUrl("https://" + ASSET_HOST + "/assets/index.html");
    }

    private void startDiscovery() {
        if (discovery != null) return;
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("fursan-lan-wifi-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            discovery = new PeerDiscovery(this);
            discovery.start();
            WifiPeerService service = new WifiPeerService(new WifiPeerService.Listener() {
                @Override public void onWifiPeerConnected(String address) {
                    JSONObject detail = new JSONObject();
                    try { detail.put("address", address); } catch (Exception ignored) { return; }
                    dispatchWifiEvent("peer-connected", detail);
                }
                @Override public void onWifiPeerDisconnected(String address) {
                    knownPeers.remove(address + ":" + WifiPeerService.PEER_PORT);
                    JSONObject detail = new JSONObject();
                    try { detail.put("address", address); } catch (Exception ignored) { return; }
                    dispatchWifiEvent("peer-disconnected", detail);
                }
                @Override public void onWifiFrame(String address, JSONObject frame) {
                    JSONObject detail = new JSONObject();
                    try { detail.put("address", address); detail.put("frame", frame); } catch (Exception ignored) { return; }
                    dispatchWifiEvent("frame", detail);
                }
                @Override public void onWifiError(String address, String message) {
                    if (address != null) knownPeers.remove(address + ":" + WifiPeerService.PEER_PORT);
                    JSONObject detail = new JSONObject();
                    try { detail.put("message", message); } catch (Exception ignored) { }
                    if (address != null) try { detail.put("address", address); } catch (Exception ignored) { }
                    dispatchWifiEvent("notice", detail);
                }
            });
            service.start();
            wifiPeerService = service;
            startWifiDirectController();
        } catch (SecurityException error) {
            dispatchNotice("يحتاج اكتشاف الشبكة إلى إذن Wi‑Fi المحلي.");
        } catch (Exception error) {
            dispatchNotice("تعذر تشغيل اتصال Wi‑Fi المباشر: " + error.getMessage());
        }
    }

    private void startWifiDirectController() {
        if (wifiDirectController != null) return;
        wifiDirectController = new WifiDirectController(this, new WifiDirectController.Listener() {
            @Override public void onStatus(String message) {
                JSONObject detail = new JSONObject();
                try { detail.put("message", message); } catch (Exception ignored) { }
                dispatchWifiEvent("wifi-direct-status", detail);
            }

            @Override public void onPeers(JSONArray peers) {
                JSONObject detail = new JSONObject();
                try { detail.put("peers", peers); } catch (Exception ignored) { }
                dispatchWifiEvent("wifi-direct-peers", detail);
            }

            @Override public void onContactFound(String clientId, String name, String deviceAddress) {
                JSONObject detail = new JSONObject();
                try {
                    detail.put("clientId", clientId);
                    detail.put("name", name);
                    detail.put("deviceAddress", deviceAddress);
                } catch (Exception ignored) { return; }
                dispatchWifiEvent("wifi-direct-contact-found", detail);
            }

            @Override public void onGroupConnected(String ownerAddress, boolean isGroupOwner) {
                handleWifiDirectConnected(ownerAddress, isGroupOwner);
            }

            @Override public void onGroupDisconnected() {
                connectedWifiDirectOwner = null;
                JSONObject detail = new JSONObject();
                try { detail.put("message", "انقطعت مجموعة Wi‑Fi Direct؛ يمكنك إنشاء مجموعة أو إعادة البحث والانضمام."); }
                catch (Exception ignored) { }
                dispatchWifiEvent("wifi-direct-status", detail);
            }

            @Override public void onError(String message) {
                JSONObject detail = new JSONObject();
                try { detail.put("message", message); } catch (Exception ignored) { }
                dispatchWifiEvent("notice", detail);
            }
        });
        wifiDirectController.start();
    }

    private String requiredWifiDirectPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void startLocalHotspot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            dispatchHotspotEvent("local-hotspot-failed", "نقطة الاتصال المحلية تتطلب Android 8.0 أو أحدث.");
            return;
        }
        synchronized (this) {
            if (localHotspotReservation != null) {
                dispatchHotspotStarted();
                return;
            }
            if (localHotspotStarting) {
                dispatchHotspotEvent("local-hotspot-status", "جارٍ إنشاء نقطة الاتصال…");
                return;
            }
            localHotspotStarting = true;
        }
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (manager == null) {
            localHotspotStarting = false;
            dispatchHotspotEvent("local-hotspot-failed", "خدمة Wi‑Fi غير متاحة على هذا الهاتف.");
            return;
        }
        WifiManager.LocalOnlyHotspotCallback callback = new WifiManager.LocalOnlyHotspotCallback() {
            @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                if (localHotspotCallback != this || isFinishing() || isDestroyed()) {
                    reservation.close();
                    return;
                }
                localHotspotReservation = reservation;
                localHotspotStarting = false;
                try {
                    String ssid;
                    String passphrase;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        SoftApConfiguration config = reservation.getSoftApConfiguration();
                        ssid = config == null ? "" : config.getSsid();
                        passphrase = config == null ? "" : config.getPassphrase();
                    } else {
                        @SuppressWarnings("deprecation") WifiConfiguration config = reservation.getWifiConfiguration();
                        ssid = config == null ? "" : config.SSID;
                        passphrase = config == null ? "" : normalizeHotspotSsid(config.preSharedKey);
                    }
                    localHotspotSsid = normalizeHotspotSsid(ssid);
                    localHotspotPassphrase = passphrase == null ? "" : passphrase;
                    if (localHotspotSsid.isEmpty()) throw new IllegalStateException("لم يعرض Android اسم نقطة الاتصال.");
                    dispatchHotspotStarted();
                } catch (Exception error) {
                    localHotspotReservation = null;
                    localHotspotCallback = null;
                    localHotspotSsid = "";
                    localHotspotPassphrase = "";
                    reservation.close();
                    dispatchHotspotEvent("local-hotspot-failed", "أُنشئت النقطة لكن تعذر قراءة بياناتها: " + safeErrorMessage(error));
                }
            }

            @Override public void onStopped() {
                if (localHotspotCallback != this) return;
                localHotspotReservation = null;
                localHotspotCallback = null;
                localHotspotStarting = false;
                localHotspotSsid = "";
                localHotspotPassphrase = "";
                dispatchHotspotEvent("local-hotspot-stopped", "توقفت نقطة الاتصال المحلية.");
            }

            @Override public void onFailed(int reason) {
                if (localHotspotCallback != this) return;
                localHotspotReservation = null;
                localHotspotCallback = null;
                localHotspotStarting = false;
                dispatchHotspotEvent("local-hotspot-failed", hotspotFailureMessage(reason));
            }
        };
        localHotspotCallback = callback;
        try {
            manager.startLocalOnlyHotspot(callback, mainHandler);
            dispatchHotspotEvent("local-hotspot-status", "طلب Android إنشاء نقطة اتصال محلية…");
        } catch (SecurityException error) {
            localHotspotStarting = false;
            localHotspotCallback = null;
            dispatchHotspotEvent("local-hotspot-failed", "اسمح بإذن الأجهزة القريبة ثم أعد المحاولة.");
        } catch (RuntimeException error) {
            localHotspotStarting = false;
            localHotspotCallback = null;
            dispatchHotspotEvent("local-hotspot-failed", "تعذر طلب نقطة الاتصال: " + safeErrorMessage(error));
        }
    }

    private void stopLocalHotspot() {
        WifiManager.LocalOnlyHotspotReservation reservation = localHotspotReservation;
        localHotspotReservation = null;
        localHotspotCallback = null;
        localHotspotStarting = false;
        localHotspotSsid = "";
        localHotspotPassphrase = "";
        if (reservation != null) {
            try { reservation.close(); }
            catch (Exception error) { Log.w(TAG, "Could not close local hotspot reservation", error); }
        }
        dispatchHotspotEvent("local-hotspot-stopped", "أوقفت نقطة الاتصال المحلية.");
    }

    private void dispatchHotspotStarted() {
        JSONObject detail = new JSONObject();
        WifiPeerService service = wifiPeerService;
        try {
            detail.put("ssid", localHotspotSsid);
            detail.put("passphrase", localHotspotPassphrase);
            detail.put("addresses", service == null ? "" : service.getLocalAddresses());
            detail.put("port", WifiPeerService.PEER_PORT);
            detail.put("message", "نقطة الاتصال المحلية جاهزة. وصّل الهاتف الآخر باسم الشبكة وكلمة المرور ثم عُد إلى التطبيق.");
        } catch (Exception ignored) { }
        dispatchWifiEvent("local-hotspot-started", detail);
    }

    private void dispatchHotspotEvent(String type, String message) {
        JSONObject detail = new JSONObject();
        try { detail.put("message", message); } catch (Exception ignored) { }
        dispatchWifiEvent(type, detail);
    }

    private static String normalizeHotspotSsid(String ssid) {
        if (ssid == null) return "";
        String value = ssid.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String hotspotFailureMessage(int reason) {
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE) {
            return "تعذر إنشاء النقطة لأن Wi‑Fi Direct أو وضع Wi‑Fi آخر يستخدم الراديو؛ أوقف الاتصال الحالي ثم أعد المحاولة.";
        }
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED) {
            return "سياسة الهاتف أو الشركة تمنع إنشاء نقطة اتصال محلية.";
        }
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL) {
            return "لا توجد قناة Wi‑Fi متاحة الآن؛ أوقف نقطة اتصال أخرى أو أعد المحاولة.";
        }
        return "رفض Android إنشاء نقطة الاتصال المحلية (الخطأ " + reason + "). أوقف hotspot أو Wi‑Fi Direct آخر ثم أعد المحاولة.";
    }

    private static String safeErrorMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String runWifiDirectAction(String action, String peerAddress) {
        if (!"hotspot-start".equals(action) && wifiDirectController == null) startWifiDirectController();
        String permission = requiredWifiDirectPermission();
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            pendingWifiDirectAction = action;
            pendingWifiDirectPeer = peerAddress;
            requestPermissions(new String[]{permission}, WIFI_DIRECT_PERMISSION_REQUEST);
            return "permission-required";
        }
        if ("hotspot-start".equals(action)) {
            startLocalHotspot();
            return "started";
        }
        WifiDirectController controller = wifiDirectController;
        if (controller == null) return "error:تعذر تهيئة Wi‑Fi Direct.";
        controller.start();
        if ("auto-discovery".equals(action)) controller.setLocalIdentity(localWifiClientId, localWifiDisplayName);
        else if ("connect-contact".equals(action)) {
            controller.setLocalIdentity(localWifiClientId, localWifiDisplayName);
            controller.connectContact(peerAddress);
        }
        else if ("create".equals(action)) controller.createGroup();
        else if ("discover".equals(action)) controller.discoverPeers();
        else if ("connect".equals(action)) controller.connect(peerAddress);
        return "started";
    }

    private void handleWifiDirectConnected(String ownerAddress, boolean isGroupOwner) {
        if (ownerAddress == null || ownerAddress.trim().isEmpty()) return;
        JSONObject detail = new JSONObject();
        try {
            detail.put("ownerAddress", ownerAddress);
            detail.put("isGroupOwner", isGroupOwner);
            detail.put("message", isGroupOwner
                    ? "أُنشئت مجموعة Wi‑Fi Direct؛ هذا الهاتف مالك المجموعة وينتظر النظير."
                    : "انضم الهاتف إلى مجموعة Wi‑Fi Direct؛ جارٍ الاتصال بمالك المجموعة.");
        } catch (Exception ignored) { }
        dispatchWifiEvent("wifi-direct-status", detail);
        if (isGroupOwner || ownerAddress.equals(connectedWifiDirectOwner)) return;
        connectedWifiDirectOwner = ownerAddress;
        WifiPeerService service = wifiPeerService;
        if (service == null) {
            dispatchNotice("خادم النظير لم يبدأ؛ أعد فتح فرسان لان ثم أعد اتصال Wi‑Fi Direct.");
            return;
        }
        try {
            service.connectManually(ownerAddress);
        } catch (Exception error) {
            connectedWifiDirectOwner = null;
            dispatchNotice("تكوّنت مجموعة Wi‑Fi Direct لكن تعذر فتح قناة المراسلة: " + error.getMessage());
        }
    }

    private boolean isKnownPeerFileUrl(Uri uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != 43821
                || !LanAddressPolicy.isPrivateLanAddress(uri.getHost())) return false;
        String path = uri.getPath();
        if (path == null || !path.matches("/api/files/[0-9a-fA-F-]{36}")) return false;
        return knownPeers.contains(uri.getHost() + ":" + uri.getPort());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady && discovery == null) startDiscovery();
        WifiDirectController controller = wifiDirectController;
        if (pageReady && controller != null && localWifiClientId != null
                && checkSelfPermission(requiredWifiDirectPermission()) == PackageManager.PERMISSION_GRANTED) {
            controller.startServiceDiscovery();
        }
    }

    @Override
    protected void onPause() {
        WifiDirectController controller = wifiDirectController;
        if (controller != null) controller.pauseServiceDiscovery();
        super.onPause();
    }

    @Override
    public void onPeer(PeerDiscovery.PeerAdvertisement peer) {
        WifiPeerService activeWifiService = wifiPeerService;
        if (activeWifiService != null && activeWifiService.getInstanceId().equalsIgnoreCase(peer.instanceId)) return;
        String endpoint = peer.address + ":" + peer.port;
        if (!knownPeers.add(endpoint)) return;
        mainHandler.post(() -> {
            if (webView == null || !pageReady) return;
            try {
                JSONObject data = new JSONObject().put("address", peer.address).put("port", peer.port).put("name", peer.name)
                        .put("transport", peer.transport).put("instanceId", peer.instanceId);
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('fursan-peer-found',{detail:" + data + "}));", null);
            } catch (Exception error) {
                Log.w(TAG, "Could not announce discovered peer", error);
            }
        });
    }

    @Override
    public void onDiscoveryError(String message) {
        discovery = null;
        dispatchNotice(message);
    }

    private void dispatchNotice(String message) {
        mainHandler.post(() -> {
            if (webView == null || !pageReady) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                return;
            }
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('fursan-notice',{detail:" + JSONObject.quote(message) + "}));", null);
        });
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized void startBluetoothService() {
        if (bluetoothService != null) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothPeerService service = new BluetoothPeerService(adapter, this);
            service.start();
            bluetoothService = service;
            dispatchBluetoothEvent("ready", new JSONObject());
        } catch (Exception error) {
            JSONObject detail = new JSONObject();
            try { detail.put("message", "تعذر تشغيل Bluetooth: " + error.getMessage()); } catch (Exception ignored) { }
            dispatchBluetoothEvent("notice", detail);
        }
    }

    private void dispatchBluetoothEvent(String type, JSONObject detail) {
        JSONObject event = new JSONObject();
        try { event.put("type", type); event.put("detail", detail); } catch (Exception ignored) { return; }
        String script = "window.dispatchEvent(new CustomEvent('fursan-bt-event',{detail:" + event.toString() + "}));";
        mainHandler.post(() -> { if (webView != null && pageReady) webView.evaluateJavascript(script, null); });
    }

    private void dispatchWifiEvent(String type, JSONObject detail) {
        JSONObject event = new JSONObject();
        try { event.put("type", type); event.put("detail", detail); } catch (Exception ignored) { return; }
        String script = "window.dispatchEvent(new CustomEvent('fursan-wifi-event',{detail:" + event.toString() + "}));";
        mainHandler.post(() -> { if (webView != null && pageReady) webView.evaluateJavascript(script, null); });
    }

    private String encodeQrPngBase64(String text) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
        Bitmap bitmap = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888);
        try {
            for (int y = 0; y < QR_SIZE; y++) for (int x = 0; x < QR_SIZE; x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF102D32 : 0xFFFFFFFF);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    @Override
    public void onPeerConnected(String address, String name) {
        JSONObject detail = new JSONObject();
        try { detail.put("address", address); detail.put("name", name); } catch (Exception ignored) { return; }
        dispatchBluetoothEvent("peer-connected", detail);
    }

    @Override
    public void onPeerDisconnected(String address) {
        JSONObject detail = new JSONObject();
        try { detail.put("address", address); } catch (Exception ignored) { return; }
        dispatchBluetoothEvent("peer-disconnected", detail);
    }

    @Override
    public void onFrame(String address, JSONObject frame) {
        JSONObject detail = new JSONObject();
        try { detail.put("address", address); detail.put("frame", frame); } catch (Exception ignored) { return; }
        dispatchBluetoothEvent("frame", detail);
    }

    @Override
    public void onError(String message) {
        JSONObject detail = new JSONObject();
        try { detail.put("message", message); } catch (Exception ignored) { }
        dispatchBluetoothEvent("notice", detail);
    }

    private final class BluetoothJsBridge {
        @JavascriptInterface
        public String start() {
            if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermission()) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_REQUEST));
                return "permission-required";
            }
            runOnUiThread(MainActivity.this::startBluetoothService);
            return "starting";
        }

        @JavascriptInterface
        public String listBondedDevices() {
            JSONArray result = new JSONArray();
            if (!hasBluetoothPermission()) return result.toString();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return result.toString();
            try {
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    JSONObject item = new JSONObject();
                    item.put("address", device.getAddress());
                    item.put("name", device.getName() == null ? "جهاز Bluetooth" : device.getName());
                    result.put(item);
                }
            } catch (Exception error) {
                Log.w(TAG, "Could not enumerate paired Bluetooth devices", error);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String connect(String address) {
            BluetoothPeerService service = bluetoothService;
            if (service == null) {
                if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermission()) return "error:امنح إذن الأجهزة القريبة ثم أعد المحاولة.";
                startBluetoothService();
                service = bluetoothService;
            }
            if (service == null) return "error:تعذر تشغيل مستمع Bluetooth على هذا الهاتف.";
            try { service.connect(address); return "connecting"; }
            catch (Exception error) { return "error:" + error.getMessage(); }
        }

        @JavascriptInterface
        public boolean send(String address, String frameJson) {
            BluetoothPeerService service = bluetoothService;
            if (service == null || frameJson == null || frameJson.length() > BluetoothFrameCodec.MAX_FRAME_BYTES) return false;
            try {
                JSONObject frame = new JSONObject(frameJson);
                if (frame.toString().getBytes(StandardCharsets.UTF_8).length > BluetoothFrameCodec.MAX_FRAME_BYTES) return false;
                return service.send(address, frame);
            }
            catch (Exception error) { return false; }
        }

        @JavascriptInterface
        public void disconnect(String address) {
            BluetoothPeerService service = bluetoothService;
            if (service != null) service.disconnect(address);
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                BluetoothPeerService service = bluetoothService;
                bluetoothService = null;
                if (service != null) service.close();
            });
        }
    }

    private final class WifiJsBridge {
        @JavascriptInterface
        public String startLocalHotspot() {
            runOnUiThread(() -> runWifiDirectAction("hotspot-start", null));
            return "starting";
        }

        @JavascriptInterface
        public void stopLocalHotspot() {
            runOnUiThread(MainActivity.this::stopLocalHotspot);
        }

        @JavascriptInterface
        public String createHotspotWifiQr() {
            JSONObject result = new JSONObject();
            try {
                if (localHotspotSsid.isEmpty()) throw new IllegalStateException("أنشئ نقطة الاتصال المحلية أولًا.");
                result.put("qrPng", encodeQrPngBase64(WifiHotspotQr.payload(localHotspotSsid, localHotspotPassphrase)));
                result.put("ssid", localHotspotSsid);
            } catch (Exception error) {
                try { result.put("error", safeErrorMessage(error)); } catch (Exception ignored) { }
            }
            return result.toString();
        }

        @JavascriptInterface
        public String createWifiDirectGroup() {
            runOnUiThread(() -> runWifiDirectAction("create", null));
            return "starting";
        }

        @JavascriptInterface
        public String startWifiDirectDiscovery(String clientId, String name) {
            if (clientId == null || !clientId.matches("^[A-Za-z0-9_-]{16,80}$")) return "error:هوية الجهاز غير صالحة.";
            localWifiClientId = clientId;
            localWifiDisplayName = name == null ? "هاتف Android" : name;
            runOnUiThread(() -> runWifiDirectAction("auto-discovery", null));
            return "starting";
        }

        @JavascriptInterface
        public String connectWifiDirectContact(String clientId) {
            if (clientId == null || !clientId.matches("^[A-Za-z0-9_-]{16,80}$")) return "error:هوية جهة الاتصال غير صالحة.";
            runOnUiThread(() -> runWifiDirectAction("connect-contact", clientId));
            return "connecting";
        }

        @JavascriptInterface
        public String discoverWifiDirectPeers() {
            runOnUiThread(() -> runWifiDirectAction("discover", null));
            return "searching";
        }

        @JavascriptInterface
        public String connectWifiDirectPeer(String address) {
            if (address == null) return "error:اختر جهازًا من قائمة Wi‑Fi Direct.";
            runOnUiThread(() -> runWifiDirectAction("connect", address));
            return "connecting";
        }

        @JavascriptInterface
        public String createPairingQr() {
            WifiPeerService service = wifiPeerService;
            if (service == null) return "{\"error\":\"خدمة Wi-Fi المباشرة غير جاهزة.\"}";
            try {
                JSONObject code = PeerPairingCode.create(service.getPairingAddress(), service.getInstanceId());
                Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
                hints.put(EncodeHintType.MARGIN, 2);
                BitMatrix matrix = new MultiFormatWriter().encode(code.toString(), BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
                Bitmap bitmap = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < QR_SIZE; y++) for (int x = 0; x < QR_SIZE; x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF102D32 : 0xFFFFFFFF);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                bitmap.recycle();
                code.put("qrPng", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                return code.toString();
            } catch (Exception error) {
                JSONObject failure = new JSONObject();
                try { failure.put("error", error.getMessage() == null ? "تعذر إنشاء رمز الاقتران." : error.getMessage()); }
                catch (Exception ignored) { }
                return failure.toString();
            }
        }

        @JavascriptInterface
        public String scanPairingQr() {
            runOnUiThread(() -> new IntentIntegrator(MainActivity.this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("امسح رمز فرسان لان الظاهر على الهاتف الآخر")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .initiateScan());
            return "scanning";
        }

        @JavascriptInterface
        public String localAddresses() {
            WifiPeerService service = wifiPeerService;
            return service == null ? "" : service.getLocalAddresses();
        }

        @JavascriptInterface
        public String connectManual(String address) {
            WifiPeerService service = wifiPeerService;
            if (service == null) return "error:خدمة الاتصال المباشر غير جاهزة؛ أعد فتح التطبيق.";
            try { service.connectManually(address == null ? "" : address.trim()); return "connecting"; }
            catch (Exception error) { return "error:" + error.getMessage(); }
        }

        @JavascriptInterface
        public String connect(String address, String port, String instanceId) {
            WifiPeerService service = wifiPeerService;
            if (service == null) return "error:خدمة Wi‑Fi المباشرة غير جاهزة؛ تحقق من اتصال Wi‑Fi ثم أعد فتح التطبيق.";
            try { return service.connect(address, Integer.parseInt(port), instanceId) ? "connecting" : "waiting-for-peer"; }
            catch (Exception error) { return "error:" + error.getMessage(); }
        }

        @JavascriptInterface
        public boolean send(String address, String frameJson) {
            WifiPeerService service = wifiPeerService;
            if (service == null || frameJson == null || frameJson.length() > BluetoothFrameCodec.MAX_FRAME_BYTES) return false;
            try {
                JSONObject frame = new JSONObject(frameJson);
                if (frame.toString().getBytes(StandardCharsets.UTF_8).length > BluetoothFrameCodec.MAX_FRAME_BYTES) return false;
                return service.send(address, frame);
            } catch (Exception error) { return false; }
        }

        @JavascriptInterface
        public void disconnect(String address) {
            WifiPeerService service = wifiPeerService;
            if (service != null) service.disconnect(address);
        }
    }

    private void connectScannedPairingCode(String raw) {
        WifiPeerService service = wifiPeerService;
        if (service == null) {
            JSONObject detail = new JSONObject();
            try { detail.put("message", "خدمة Wi-Fi المباشرة غير جاهزة؛ أعد فتح التطبيق."); } catch (Exception ignored) { }
            dispatchWifiEvent("notice", detail);
            return;
        }
        try {
            String address = PeerPairingCode.parseAddress(raw);
            service.connectManually(address);
            JSONObject detail = new JSONObject();
            detail.put("address", address);
            dispatchWifiEvent("pairing-started", detail);
        } catch (Exception error) {
            JSONObject detail = new JSONObject();
            try { detail.put("message", error.getMessage() == null ? "تعذر قراءة رمز الاقتران." : error.getMessage()); }
            catch (Exception ignored) { }
            dispatchWifiEvent("notice", detail);
        }
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request.getOrigin() == null || !ASSET_HOST.equals(request.getOrigin().getHost())) {
            request.deny();
            return;
        }
        ArrayList<String> androidPermissions = new ArrayList<>();
        ArrayList<String> webResources = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                webResources.add(resource);
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) androidPermissions.add(Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                webResources.add(resource);
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) androidPermissions.add(Manifest.permission.CAMERA);
            }
        }
        if (webResources.isEmpty()) {
            request.deny();
            return;
        }
        if (androidPermissions.isEmpty()) {
            request.grant(webResources.toArray(new String[0]));
            return;
        }
        if (pendingMediaRequest != null) pendingMediaRequest.deny();
        pendingMediaRequest = request;
        pendingWebResources = webResources.toArray(new String[0]);
        requestPermissions(androidPermissions.toArray(new String[0]), MEDIA_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLUETOOTH_REQUEST) {
            if (hasBluetoothPermission()) startBluetoothService();
            else {
                JSONObject detail = new JSONObject();
                try { detail.put("message", "يلزم إذن الأجهزة القريبة لاستخدام Bluetooth."); } catch (Exception ignored) { }
                dispatchBluetoothEvent("notice", detail);
            }
            return;
        }
        if (requestCode == WIFI_DIRECT_PERMISSION_REQUEST) {
            String action = pendingWifiDirectAction;
            String peerAddress = pendingWifiDirectPeer;
            pendingWifiDirectAction = null;
            pendingWifiDirectPeer = null;
            if (checkSelfPermission(requiredWifiDirectPermission()) == PackageManager.PERMISSION_GRANTED && action != null) {
                runWifiDirectAction(action, peerAddress);
            } else {
                dispatchNotice(Build.VERSION.SDK_INT >= 33
                        ? "يلزم السماح بإذن الأجهزة القريبة لاستخدام Wi‑Fi Direct أو نقطة الاتصال المحلية."
                        : "يلزم إذن الموقع للاتصال اللاسلكي على إصدار Android القديم؛ لا يستخدم فرسان لان موقعك.");
            }
            return;
        }
        if (requestCode != MEDIA_REQUEST || pendingMediaRequest == null) return;
        ArrayList<String> allowed = new ArrayList<>();
        for (String resource : pendingWebResources) {
            boolean granted = PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    ? checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    : checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (granted) allowed.add(resource);
        }
        if (allowed.isEmpty()) pendingMediaRequest.deny();
        else pendingMediaRequest.grant(allowed.toArray(new String[0]));
        pendingMediaRequest = null;
        pendingWebResources = new String[0];
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null) connectScannedPairingCode(scan.getContents());
            return;
        }
        if (requestCode != FILE_REQUEST || fileChooserCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData clip = data.getClipData();
                results = new Uri[clip.getItemCount()];
                for (int index = 0; index < clip.getItemCount(); index++) results[index] = clip.getItemAt(index).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(results);
        fileChooserCallback = null;
    }

    private void downloadFile(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (filename == null || filename.trim().isEmpty()) filename = "fursan-file";
            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            request.setTitle(filename);
            request.setDescription("تنزيل ملف من فرسان لان عبر Wi‑Fi");
            if (mimeType != null) request.setMimeType(mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            request.setAllowedOverMetered(true);
            manager.enqueue(request);
            Toast.makeText(this, "بدأ التنزيل إلى مجلد Downloads.", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Log.e(TAG, "Download failed", error);
            Toast.makeText(this, "تعذر تنزيل الملف: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopLocalHotspot();
        WifiDirectController activeWifiDirect = wifiDirectController;
        wifiDirectController = null;
        if (activeWifiDirect != null) activeWifiDirect.close();
        BluetoothPeerService activeBluetooth = bluetoothService;
        bluetoothService = null;
        if (activeBluetooth != null) activeBluetooth.close();
        WifiPeerService activeWifi = wifiPeerService;
        wifiPeerService = null;
        if (activeWifi != null) activeWifi.close();
        if (discovery != null) discovery.close();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
