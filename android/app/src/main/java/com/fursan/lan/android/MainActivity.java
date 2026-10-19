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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements PeerDiscovery.Listener, BluetoothPeerService.Listener {
    private static final String TAG = "FursanLanAndroid";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final int FILE_REQUEST = 7101;
    private static final int MEDIA_REQUEST = 7102;
    private static final int BLUETOOTH_REQUEST = 7103;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> knownPeers = ConcurrentHashMap.newKeySet();
    private WebView webView;
    private PeerDiscovery discovery;
    private volatile WifiPeerService wifiPeerService;
    private volatile BluetoothPeerService bluetoothService;
    private ConnectivityManager connectivityManager;
    private WifiManager.MulticastLock multicastLock;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingMediaRequest;
    private String[] pendingWebResources = new String[0];
    private boolean pageReady;
    private boolean processBoundToWifi;

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
            if (!bindToWifiNetwork()) {
                dispatchNotice("اتصل بشبكة Wi‑Fi محلية لاكتشاف أقران فرسان لان.");
                return;
            }
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("fursan-lan-wifi-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            discovery = new PeerDiscovery(this);
            discovery.start();
            WifiPeerService service = new WifiPeerService(this, new WifiPeerService.Listener() {
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
        } catch (SecurityException error) {
            dispatchNotice("يحتاج اكتشاف الشبكة إلى إذن Wi‑Fi المحلي.");
        } catch (Exception error) {
            dispatchNotice("تعذر تشغيل اتصال Wi‑Fi المباشر: " + error.getMessage());
        }
    }

    private boolean bindToWifiNetwork() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                processBoundToWifi = connectivityManager.bindProcessToNetwork(network);
                if (processBoundToWifi) return true;
            }
        }
        return false;
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
        BluetoothPeerService activeBluetooth = bluetoothService;
        bluetoothService = null;
        if (activeBluetooth != null) activeBluetooth.close();
        WifiPeerService activeWifi = wifiPeerService;
        wifiPeerService = null;
        if (activeWifi != null) activeWifi.close();
        if (discovery != null) discovery.close();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (processBoundToWifi && connectivityManager != null) connectivityManager.bindProcessToNetwork(null);
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
