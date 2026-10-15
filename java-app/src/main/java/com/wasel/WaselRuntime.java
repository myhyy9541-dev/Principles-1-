package com.wasel;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WaselRuntime implements AutoCloseable {
    private static final Gson GSON = new Gson();
    public static final int UI_PORT = 43819;
    private final Path dataRoot, settingsPath;
    private final ConcurrentLinkedQueue<JsonObject> events = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private SettingsStore.Settings settings;
    private final RoomStore store;
    private final LanDiscovery discovery;
    private HttpServer uiServer;
    private FileServer fileServer;
    private LanRoomServer roomServer;

    public WaselRuntime() throws IOException {
        dataRoot = resolveDataRoot(); settingsPath = dataRoot.resolve("settings.json"); Files.createDirectories(dataRoot);
        migrateLegacyRoom(dataRoot);
        settings = SettingsStore.load(settingsPath, SettingsStore.adapters());
        store = new RoomStore(dataRoot.resolve("wasel-room"));
        try { AutoStart.sync(settings.openAtLogin); } catch (Exception ex) { queueNotice("تعذر تطبيق بدء Windows التلقائي: " + ex.getMessage()); }
        discovery = new LanDiscovery(InetAddress.getLocalHost().getHostName(), settings, this::onRoomFound);
        uiServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), UI_PORT), 16);
        uiServer.createContext("/api/lan-info", this::handleLanInfo);
        uiServer.createContext("/api/settings", this::handleSettings);
        uiServer.createContext("/api/start-room", this::handleStartRoom);
        uiServer.createContext("/api/events", this::handleEvents);
        uiServer.createContext("/", this::handleStatic);
        uiServer.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "wasel-local-http"); t.setDaemon(true); return t; }));
        uiServer.start();
    }

    public String startUrl() { return "http://127.0.0.1:" + UI_PORT + "/"; }
    public Path dataRoot() { return dataRoot; }

    public static Path resolveDataRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) return Path.of(appData, "wasel-lan");
        }
        return Path.of(System.getProperty("user.home"), ".config", "wasel-lan");
    }

    private static void migrateLegacyRoom(Path dataRoot) {
        if (Files.exists(dataRoot.resolve("wasel-room"))) return;
        List<Path> candidates = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            candidates.add(Path.of(appData, "wasel-lan", "wasel-room"));
            candidates.add(Path.of(appData, "واصل - تواصل المنشأة", "wasel-room"));
            candidates.add(Path.of(appData, "com.wasel.lan", "wasel-room"));
        }
        for (Path old : candidates) {
            if (!Files.isDirectory(old) || old.toAbsolutePath().normalize().equals(dataRoot.resolve("wasel-room").toAbsolutePath().normalize())) continue;
            try (var walk = Files.walk(old)) {
                for (Path item : walk.toList()) {
                    if (item.getFileName().toString().endsWith(".part")) continue;
                    Path target = dataRoot.resolve("wasel-room").resolve(old.relativize(item));
                    if (Files.isDirectory(item)) Files.createDirectories(target);
                    else if (Files.isRegularFile(item)) { Files.createDirectories(target.getParent()); Files.copy(item,target,StandardCopyOption.COPY_ATTRIBUTES); }
                }
                return;
            } catch (IOException ex) { System.err.println("Legacy Wasel data migration: " + ex.getMessage()); }
        }
    }

    private void onRoomFound(JsonObject room) { if (events.size() < 256) event("room-found", room); }
    private void event(String type, Object data) { JsonObject item = new JsonObject(); item.addProperty("type", type); item.add("data", GSON.toJsonTree(data)); if (events.size() > 256) events.poll(); events.offer(item); }
    private void queueNotice(String text) { event("notice", text); }

    private void handleLanInfo(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        JsonObject out = new JsonObject(); out.addProperty("hostname", safeHost()); out.addProperty("platform", System.getProperty("os.name"));
        JsonArray addresses = new JsonArray();
        SettingsStore.Adapter selected = SettingsStore.adapters().stream().filter(a -> a.id.equals(settings.networkInterface)).findFirst().orElse(null);
        if (selected != null) addresses.add(selected.address); else SettingsStore.adapters().forEach(a -> { if (!addresses.contains(GSON.toJsonTree(a.address))) addresses.add(a.address); });
        out.add("addresses", addresses); out.addProperty("port", LanDiscovery.SOCKET_PORT); out.addProperty("filePort", LanDiscovery.FILE_PORT); json(exchange,200,out);
    }

    private void handleSettings(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) { json(exchange,200,settingsView()); return; }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        try {
            String body = new String(exchange.getRequestBody().readNBytes(64 * 1024),StandardCharsets.UTF_8);
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            SettingsStore.Settings candidate = GSON.fromJson(request.has("settings") ? request.get("settings") : request,SettingsStore.Settings.class);
            SettingsStore.Settings next = SettingsStore.normalize(candidate,SettingsStore.adapters());
            AutoStart.sync(next.openAtLogin);
            settings = SettingsStore.save(settingsPath,next,SettingsStore.adapters()); discovery.updateSettings(settings);
            JsonObject view=settingsView(); event("settings-updated",view); json(exchange,200,view);
        } catch (Exception ex) { json(exchange,400,error(ex.getMessage()==null?"تعذر حفظ الإعدادات.":ex.getMessage())); }
    }

    private JsonObject settingsView() {
        JsonObject out=new JsonObject(); out.add("settings",SettingsStore.toJson(settings)); out.add("adapters",GSON.toJsonTree(SettingsStore.adapters()));
        out.addProperty("hostname",safeHost()); out.addProperty("appVersion","0.4.0"); out.addProperty("platform",System.getProperty("os.name")); return out;
    }

    private synchronized void handleStartRoom(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        try {
            if (roomServer == null) {
                LanRoomServer socket = new LanRoomServer(store,this::queueNotice); FileServer files = new FileServer(store);
                try { socket.start(); files.start(); roomServer=socket; fileServer=files; discovery.setHosting(true); }
                catch(Exception ex) { socket.close(); files.close(); throw ex; }
                JsonObject started=new JsonObject(); started.add("addresses",lanInfoAddresses()); started.addProperty("port",LanDiscovery.SOCKET_PORT); started.addProperty("filePort",LanDiscovery.FILE_PORT); event("room-started",started);
            }
            JsonObject result=new JsonObject(); result.addProperty("ok",true); result.addProperty("port",LanDiscovery.SOCKET_PORT); json(exchange,200,result);
        } catch (Exception ex) { json(exchange,200,error(ex.getMessage()==null?"تعذر فتح الغرفة على الشبكة.":ex.getMessage())); }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        JsonArray out=new JsonArray(); JsonObject item; int count=0; while(count++<128 && (item=events.poll())!=null) out.add(item); json(exchange,200,out);
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        String path=exchange.getRequestURI().getPath(); if (path==null || "/".equals(path)) path="/index.html";
        String resource="public/"+path.substring(1);
        if (resource.contains("..") || resource.startsWith("public/node_modules/")) { reply(exchange,404,"غير موجود."); return; }
        try (var input=WaselRuntime.class.getClassLoader().getResourceAsStream(resource)) {
            if(input==null){reply(exchange,404,"غير موجود.");return;}
            byte[] data=input.readAllBytes(); exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff"); exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("Content-Type",contentType(resource)); exchange.sendResponseHeaders(200,"HEAD".equalsIgnoreCase(exchange.getRequestMethod())?-1:data.length);
            if(!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) try(var output=exchange.getResponseBody()){output.write(data);}
        }
    }

    private JsonArray lanInfoAddresses() { JsonArray out=new JsonArray(); SettingsStore.Adapter selected=SettingsStore.adapters().stream().filter(a->a.id.equals(settings.networkInterface)).findFirst().orElse(null); if(selected!=null)out.add(selected.address);else SettingsStore.adapters().forEach(a->{if(!out.contains(GSON.toJsonTree(a.address)))out.add(a.address);}); return out; }
    private static String safeHost(){try{return InetAddress.getLocalHost().getHostName();}catch(Exception ex){return "Wasel";}}
    private static String contentType(String path){String lower=path.toLowerCase(Locale.ROOT); if(lower.endsWith(".html"))return"text/html; charset=utf-8";if(lower.endsWith(".js"))return"text/javascript; charset=utf-8";if(lower.endsWith(".css"))return"text/css; charset=utf-8";if(lower.endsWith(".png"))return"image/png";if(lower.endsWith(".svg"))return"image/svg+xml";if(lower.endsWith(".ico"))return"image/x-icon";return"application/octet-stream";}
    private static JsonObject error(String message){JsonObject result=new JsonObject();result.addProperty("ok",false);result.addProperty("error",message);return result;}
    private static void json(HttpExchange exchange,int status,JsonElement data)throws IOException{byte[] body=GSON.toJson(data).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,body.length);try(var output=exchange.getResponseBody()){output.write(body);}}
    private static void reply(HttpExchange exchange,int status,String text)throws IOException{json(exchange,status,error(text));}

    @Override public synchronized void close(){if(!closed.compareAndSet(false,true))return;discovery.setHosting(false);if(roomServer!=null)roomServer.close();if(fileServer!=null)fileServer.close();discovery.close();if(uiServer!=null)uiServer.stop(1);}
}
