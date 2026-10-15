package com.wasel;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class FileServer implements AutoCloseable {
    private final RoomStore store;
    private HttpServer server;
    public FileServer(RoomStore store) { this.store = store; }

    public synchronized void start() throws IOException {
        if (server != null) return;
        HttpServer next = HttpServer.create(new InetSocketAddress("0.0.0.0", LanDiscovery.FILE_PORT), 32);
        next.createContext("/api/files/", this::handleFile);
        next.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "wasel-file-http"); t.setDaemon(true); return t; }));
        next.start(); server = next;
    }

    private void handleFile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange, 405, "الطلب غير مدعوم."); return; }
        String raw = exchange.getRequestURI().getRawPath();
        String id = raw.substring("/api/files/".length());
        try { id = URLDecoder.decode(id, StandardCharsets.UTF_8); } catch (IllegalArgumentException ex) { reply(exchange, 400, "معرف الملف غير صالح."); return; }
        Path file = store.filePath(id); JsonObject item = store.message(id);
        if (file == null || item == null) { reply(exchange, 404, "الملف غير موجود."); return; }
        String mime = text(item, "mime", "application/octet-stream").toLowerCase(Locale.ROOT);
        boolean preview = exchange.getRequestURI().getRawQuery() != null && exchange.getRequestURI().getRawQuery().contains("preview=1");
        if (preview && !mime.matches("image/(png|jpeg|gif|webp|avif)")) { reply(exchange, 415, "معاينة هذا النوع غير مدعومة."); return; }
        String name = text(item, "name", "shared-file").replaceAll("[\\r\\n\\\"]", "_");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Type", preview ? mime : "application/octet-stream");
        if (!preview) exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        long size = Files.size(file); exchange.sendResponseHeaders(200, size);
        try (var output = exchange.getResponseBody(); var input = Files.newInputStream(file)) { input.transferTo(output); }
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
    private static String text(JsonObject object, String key, String fallback) { return LanDiscovery.text(object, key, fallback); }
    @Override public synchronized void close() { if (server != null) { server.stop(1); server = null; } }
}
