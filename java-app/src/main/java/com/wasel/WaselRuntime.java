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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

public final class WaselRuntime implements AutoCloseable {
    private static final Gson GSON = new Gson();
    public static final int UI_PORT = 43819;
    private final Path dataRoot, settingsPath;
    private final PrintPermissionStore printPermissions;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<JsonObject>> eventQueues = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService printExecutor = Executors.newFixedThreadPool(2, r -> { Thread t=new Thread(r,"wasel-printer-worker");t.setDaemon(true);return t; });
    private final Semaphore printSlots = new Semaphore(2);
    private SettingsStore.Settings settings;
    private final RoomStore store;
    private final LanDiscovery discovery;
    private HttpServer uiServer;
    private FileServer fileServer;
    private LanRoomServer roomServer;
    private LanRoomServer peerServer;
    private volatile Consumer<CompletableFuture<Path>> directoryChooser;
    private volatile Function<JsonObject, CompletableFuture<String>> windowController;

    public WaselRuntime() throws IOException {
        dataRoot = resolveDataRoot(); settingsPath = dataRoot.resolve("settings.json"); Files.createDirectories(dataRoot);
        printPermissions = new PrintPermissionStore(dataRoot.resolve("printer-permissions.json"));
        migrateLegacyRoom(dataRoot);
        settings = SettingsStore.load(settingsPath, SettingsStore.adapters());
        store = new RoomStore(dataRoot.resolve("wasel-room"));
        try { AutoStart.sync(settings.openAtLogin); } catch (Exception ex) { queueNotice("تعذر تطبيق بدء Windows التلقائي: " + ex.getMessage()); }
        discovery = new LanDiscovery(InetAddress.getLocalHost().getHostName(), settings, this::onRoomFound, this::onPeerFound);
        uiServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), UI_PORT), 16);
        uiServer.createContext("/api/lan-info", this::handleLanInfo);
        uiServer.createContext("/api/settings", this::handleSettings);
        uiServer.createContext("/api/choose-save-directory", this::handleChooseSaveDirectory);
        uiServer.createContext("/api/printers", this::handlePrinters);
        uiServer.createContext("/api/printer-permissions", this::handlePrinterPermissions);
        uiServer.createContext("/api/print-local", this::handlePrintLocal);
        uiServer.createContext("/api/window", this::handleWindow);
        uiServer.createContext("/api/start-room", this::handleStartRoom);
        uiServer.createContext("/api/start-peer", this::handleStartPeer);
        uiServer.createContext("/api/stop-peer", this::handleStopPeer);
        uiServer.createContext("/api/events", this::handleEvents);
        uiServer.createContext("/", this::handleStatic);
        uiServer.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "wasel-local-http"); t.setDaemon(true); return t; }));
        uiServer.start();
    }

    public String startUrl() { return "http://127.0.0.1:" + UI_PORT + "/"; }
    public Path dataRoot() { return dataRoot; }
    public Path defaultSaveDirectory() {
        String configured = settings.defaultSaveDirectory;
        return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath().normalize();
    }
    public void setDirectoryChooser(Consumer<CompletableFuture<Path>> chooser) { directoryChooser = chooser; }
    public void setWindowController(Function<JsonObject, CompletableFuture<String>> controller) { windowController = controller; }

    private void handleWindow(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        Function<JsonObject, CompletableFuture<String>> controller = windowController;
        if (controller == null) { reply(exchange,503,"إدارة النوافذ غير متاحة."); return; }
        try {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(8192),StandardCharsets.UTF_8)).getAsJsonObject();
            String action = request.has("action") ? request.get("action").getAsString() : "";
            String view = request.has("view") ? request.get("view").getAsString() : "";
            if (!List.of("open","show","hide","close","unregister").contains(action)) { reply(exchange,400,"إجراء النافذة غير صالح."); return; }
            if ("unregister".equals(action)) {
                String windowId = request.has("windowId") ? request.get("windowId").getAsString() : "";
                eventQueues.remove(windowId);
                JsonObject response = new JsonObject(); response.addProperty("ok",true); response.addProperty("windowId",windowId); json(exchange,200,response); return;
            }
            if ("open".equals(action) && !List.of("workspace","call").contains(view)) { reply(exchange,400,"نوع النافذة غير مدعوم."); return; }
            if ("open".equals(action) && "workspace".equals(view)) {
                String target = request.has("targetId") ? request.get("targetId").getAsString() : "";
                if (!"general".equals(target) && !target.matches("[A-Za-z0-9_-]{16,80}")) { reply(exchange,400,"معرّف المستخدم غير صالح."); return; }
                String name = request.has("targetName") ? request.get("targetName").getAsString() : "مستخدم";
                request.addProperty("targetName",name.replaceAll("[\\r\\n\\u0000]"," ").substring(0,Math.min(40,name.replaceAll("[\\r\\n\\u0000]"," ").length())));
            }
            CompletableFuture<String> result = controller.apply(request);
            String windowId = result.get(10,TimeUnit.SECONDS);
            JsonObject response = new JsonObject(); response.addProperty("ok",true); response.addProperty("windowId",windowId == null ? "" : windowId); json(exchange,200,response);
        } catch (TimeoutException ex) { reply(exchange,504,"انتهت مهلة فتح النافذة."); }
        catch (Exception ex) { reply(exchange,400,ex.getCause() == null ? "تعذر تنفيذ إجراء النافذة." : String.valueOf(ex.getCause().getMessage())); }
    }

    private void handleChooseSaveDirectory(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        Consumer<CompletableFuture<Path>> chooser = directoryChooser;
        if (chooser == null) { json(exchange,503,error("اختيار المجلد غير متاح.")); return; }
        CompletableFuture<Path> selected = new CompletableFuture<>();
        try {
            chooser.accept(selected);
            Path result = selected.get(120, TimeUnit.SECONDS);
            JsonObject response = new JsonObject(); response.addProperty("ok", true);
            response.addProperty("path", result == null ? "" : result.toAbsolutePath().normalize().toString()); json(exchange,200,response);
        } catch (TimeoutException ex) { json(exchange,504,error("انتهت مهلة اختيار المجلد.")); }
        catch (Exception ex) { json(exchange,200,error("تعذر اختيار المجلد.")); }
    }

    private void handlePrinters(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        JsonObject result = new JsonObject(); result.add("printers", PrinterService.list()); result.add("permissions", printPermissions.list());
        result.add("sharedPrinterIds", GSON.toJsonTree(settings.sharedPrinterIds)); result.addProperty("shareEnabled", settings.sharePrinters); json(exchange,200,result);
    }

    private void handlePrinterPermissions(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        try {
            JsonObject request=JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(16*1024),StandardCharsets.UTF_8)).getAsJsonObject();
            String senderId=request.has("senderClientId")?request.get("senderClientId").getAsString():"";
            String printerId=request.has("printerId")?request.get("printerId").getAsString():"";
            String printerName=request.has("printerName")?request.get("printerName").getAsString():"";
            String senderName=request.has("senderName")?request.get("senderName").getAsString():"";
            if (request.has("revoke") && request.get("revoke").getAsBoolean()) printPermissions.revoke(senderId,printerId);
            else {
                if (!settings.sharePrinters || !settings.sharedPrinterIds.contains(printerId)) throw new IllegalArgumentException("فعّل مشاركة هذه الطابعة أولاً في إعدادات واصل.");
                boolean exists=PrinterService.list().asList().stream().anyMatch(item->printerId.equals(item.getAsJsonObject().get("id").getAsString()));
                if (!exists) throw new IllegalArgumentException("الطابعة المختارة لم تعد متاحة.");
                printPermissions.grant(senderId,senderName,printerId,printerName);
            }
            JsonObject result=new JsonObject(); result.addProperty("ok",true); result.add("permissions",printPermissions.list()); json(exchange,200,result);
        } catch (Exception ex) { json(exchange,400,error(ex.getMessage()==null?"تعذرت إدارة إذن الطباعة.":ex.getMessage())); }
    }

    private void handlePrintLocal(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        String senderId=exchange.getRequestHeaders().getFirst("X-Wasel-Sender"), printerId=exchange.getRequestHeaders().getFirst("X-Wasel-Printer");
        String encodedName=exchange.getRequestHeaders().getFirst("X-Wasel-Filename");
        if (senderId==null || printerId==null || encodedName==null || !settings.sharePrinters || !settings.sharedPrinterIds.contains(printerId)
                || !printPermissions.allowed(senderId,printerId)) { json(exchange,403,error("لا يوجد إذن طباعة نشط لهذا المرسل والطابعة.")); return; }
        byte[] data=exchange.getRequestBody().readNBytes(32*1024*1024+1);
        if (data.length<1 || data.length>32*1024*1024) { json(exchange,413,error("حجم مهمة الطباعة يتجاوز الحد 32 ميغابايت.")); return; }
        String name;
        try { name=java.net.URLDecoder.decode(encodedName,StandardCharsets.UTF_8).replaceAll("[\\r\\n\\u0000/\\\\]","_"); }
        catch (Exception ex) { json(exchange,400,error("اسم ملف الطباعة غير صالح.")); return; }
        if(!printSlots.tryAcquire()){json(exchange,429,error("توجد مهمتا طباعة قيد التنفيذ؛ حاول مجدداً بعد اكتمالهما."));return;}
        Path temp=null;
        try {
            Path printDir=dataRoot.resolve("print-queue"); Files.createDirectories(printDir); Path jobFile=Files.createTempFile(printDir,"job-",".tmp"); temp=jobFile;
            Files.write(jobFile,data); String status=CompletableFuture.supplyAsync(()->{try{return PrinterService.submit(jobFile,name,printerId);}catch(Exception ex){throw new java.util.concurrent.CompletionException(ex);}},printExecutor).get(); JsonObject result=new JsonObject(); result.addProperty("ok",true); result.addProperty("status",status); json(exchange,200,result);
            queueNotice("طباعة «"+name+"»: "+status);
        } catch (Exception ex) { Throwable cause=ex instanceof java.util.concurrent.ExecutionException&&ex.getCause()!=null?ex.getCause():ex; json(exchange,422,error(cause.getMessage()==null?"تعذرت طباعة الملف.":cause.getMessage())); }
        finally { try { if(temp!=null)Files.deleteIfExists(temp); } finally { printSlots.release(); } }
    }

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

    private void onRoomFound(JsonObject room) { event("room-found", room); }
    private void onPeerFound(JsonObject peer) { event("peer-found", peer); }
    private void event(String type, Object data) {
        JsonObject item = new JsonObject(); item.addProperty("type", type); item.add("data", GSON.toJsonTree(data));
        if (eventQueues.isEmpty()) eventQueues.computeIfAbsent("monitor", ignored -> new ConcurrentLinkedQueue<>());
        for (ConcurrentLinkedQueue<JsonObject> queue : eventQueues.values()) { while (queue.size() >= 256) queue.poll(); queue.offer(item.deepCopy()); }
    }
    private void queueNotice(String text) { event("notice", text); }

    private void handleLanInfo(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        JsonObject out = new JsonObject(); out.addProperty("hostname", safeHost()); out.addProperty("platform", System.getProperty("os.name"));
        JsonArray addresses = new JsonArray();
        SettingsStore.Adapter selected = SettingsStore.adapters().stream().filter(a -> a.id.equals(settings.networkInterface)).findFirst().orElse(null);
        if (selected != null) addresses.add(selected.address); else SettingsStore.adapters().forEach(a -> { if (!addresses.contains(GSON.toJsonTree(a.address))) addresses.add(a.address); });
        out.add("addresses", addresses); out.addProperty("port", LanDiscovery.SOCKET_PORT); out.addProperty("filePort", LanDiscovery.FILE_PORT); out.addProperty("peerPort", LanDiscovery.PEER_PORT); json(exchange,200,out);
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
        out.addProperty("hostname",safeHost()); out.addProperty("appVersion","0.9.2"); out.addProperty("platform",System.getProperty("os.name")); return out;
    }

    private synchronized void handleStartPeer(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        LanRoomServer nextPeer = null; FileServer nextFiles = null; boolean createdFiles = false;
        try {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(64 * 1024),StandardCharsets.UTF_8)).getAsJsonObject();
            String clientId = request.has("clientId") ? request.get("clientId").getAsString() : "";
            String name = request.has("name") ? request.get("name").getAsString().replaceAll("[\\r\\n\\u0000]"," ").strip() : "";
            if (!clientId.matches("[A-Za-z0-9_-]{16,80}")) throw new IllegalArgumentException("هوية الجهاز غير صالحة؛ أعد تشغيل التطبيق.");
            if (name.isBlank()) throw new IllegalArgumentException("اكتب الاسم الظاهر في الإعدادات قبل بدء الاتصال المباشر.");
            name = name.substring(0,Math.min(40,name.length()));
            if (peerServer == null) {
                nextPeer = new LanRoomServer(store,this::queueNotice,LanDiscovery.PEER_PORT,this::sharedPrinterServices);
                if (fileServer == null) { nextFiles = new FileServer(store); nextFiles.start(); createdFiles = true; }
                nextPeer.start(); peerServer = nextPeer;
                if (createdFiles) fileServer = nextFiles;
            }
            discovery.setPeerProfile(clientId,name,true);
            JsonObject result = new JsonObject(); result.addProperty("ok",true); result.addProperty("peerPort",LanDiscovery.PEER_PORT); result.addProperty("filePort",LanDiscovery.FILE_PORT); json(exchange,200,result);
        } catch (Exception ex) {
            if (nextPeer != null && peerServer != nextPeer) nextPeer.close();
            if (createdFiles && nextFiles != null && peerServer == null) nextFiles.close();
            json(exchange,200,error(ex.getMessage()==null?"تعذر بدء الاتصال المباشر.":ex.getMessage()));
        }
    }

    private synchronized void handleStopPeer(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        discovery.setPeerProfile("","",false);
        if (peerServer != null) { peerServer.close(); peerServer = null; }
        if (roomServer == null && fileServer != null) { fileServer.close(); fileServer = null; }
        JsonObject result = new JsonObject(); result.addProperty("ok",true); json(exchange,200,result);
    }

    private synchronized void handleStartRoom(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        try {
        if (roomServer == null) {
                LanRoomServer socket = new LanRoomServer(store,this::queueNotice,LanDiscovery.SOCKET_PORT,this::sharedPrinterServices); FileServer files = fileServer; boolean createdFiles = false;
                try { socket.start(); if (files == null) { files = new FileServer(store); files.start(); createdFiles = true; } roomServer=socket; if (createdFiles) fileServer=files; discovery.setHosting(true); }
                catch(Exception ex) { socket.close(); if (createdFiles && files != null) files.close(); throw ex; }
                JsonObject started=new JsonObject(); started.add("addresses",lanInfoAddresses()); started.addProperty("port",LanDiscovery.SOCKET_PORT); started.addProperty("filePort",LanDiscovery.FILE_PORT); event("room-started",started);
            }
            JsonObject result=new JsonObject(); result.addProperty("ok",true); result.addProperty("port",LanDiscovery.SOCKET_PORT); json(exchange,200,result);
        } catch (Exception ex) { json(exchange,200,error(ex.getMessage()==null?"تعذر فتح الغرفة على الشبكة.":ex.getMessage())); }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange,405,"الطلب غير مدعوم."); return; }
        String windowId = queryParameter(exchange.getRequestURI().getRawQuery(),"windowId");
        if (!windowId.matches("[A-Za-z0-9_-]{1,80}")) { reply(exchange,400,"معرّف النافذة غير صالح."); return; }
        if (!eventQueues.containsKey(windowId) && eventQueues.size() >= 64) { reply(exchange,429,"عدد نوافذ واصل المفتوحة تجاوز الحد الآمن."); return; }
        ConcurrentLinkedQueue<JsonObject> queue = eventQueues.computeIfAbsent(windowId, ignored -> new ConcurrentLinkedQueue<>());
        JsonArray out=new JsonArray(); JsonObject item; int count=0; while(count++<128 && (item=queue.poll())!=null) out.add(item); json(exchange,200,out);
    }

    private static String queryParameter(String query,String key) {
        if (query == null) return "";
        for (String item : query.split("&")) { String[] pair=item.split("=",2); if (pair.length==2 && key.equals(pair[0])) try { return java.net.URLDecoder.decode(pair[1],StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) { return ""; } }
        return "";
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
    private JsonArray sharedPrinterServices(){JsonArray out=new JsonArray();if(settings==null||!settings.sharePrinters||settings.sharedPrinterIds==null)return out;for(JsonElement item:PrinterService.list())if(settings.sharedPrinterIds.contains(item.getAsJsonObject().get("id").getAsString()))out.add(item);return out;}
    private static String safeHost(){try{return InetAddress.getLocalHost().getHostName();}catch(Exception ex){return "Wasel";}}
    private static String contentType(String path){String lower=path.toLowerCase(Locale.ROOT); if(lower.endsWith(".html"))return"text/html; charset=utf-8";if(lower.endsWith(".js"))return"text/javascript; charset=utf-8";if(lower.endsWith(".css"))return"text/css; charset=utf-8";if(lower.endsWith(".png"))return"image/png";if(lower.endsWith(".svg"))return"image/svg+xml";if(lower.endsWith(".ico"))return"image/x-icon";return"application/octet-stream";}
    private static JsonObject error(String message){JsonObject result=new JsonObject();result.addProperty("ok",false);result.addProperty("error",message);return result;}
    private static void json(HttpExchange exchange,int status,JsonElement data)throws IOException{byte[] body=GSON.toJson(data).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,body.length);try(var output=exchange.getResponseBody()){output.write(body);}}
    private static void reply(HttpExchange exchange,int status,String text)throws IOException{json(exchange,status,error(text));}

    @Override public synchronized void close(){if(!closed.compareAndSet(false,true))return;printExecutor.shutdownNow();discovery.setHosting(false);discovery.setPeerProfile("","",false);if(roomServer!=null)roomServer.close();if(peerServer!=null)peerServer.close();if(fileServer!=null)fileServer.close();discovery.close();if(uiServer!=null)uiServer.stop(1);store.close();}
}
