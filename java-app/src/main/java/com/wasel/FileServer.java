package com.wasel;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class FileServer implements AutoCloseable {
    private static final int STREAM_BUFFER_BYTES = 256 * 1024;
    private static final long MAX_PREVIEW_BYTES = 8L * 1024 * 1024;
    private static final long MAX_DOCUMENT_PREVIEW_BYTES = 32L * 1024 * 1024;
    private final RoomStore store;
    private final int port;
    private HttpServer server;
    public FileServer(RoomStore store) { this(store, LanDiscovery.FILE_PORT); }
    FileServer(RoomStore store, int port) { this.store = store; this.port = port; }

    public synchronized void start() throws IOException {
        if (server != null) return;
        HttpServer next = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
        next.createContext("/api/files/", this::handleFile);
        next.setExecutor(Executors.newFixedThreadPool(16, r -> { Thread t = new Thread(r, "wasel-file-http"); t.setDaemon(true); return t; }));
        next.start(); server = next;
    }
    int port() { return server == null ? -1 : server.getAddress().getPort(); }

    private void handleFile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) { reply(exchange, 405, "الطلب غير مدعوم."); return; }
        String raw = exchange.getRequestURI().getRawPath();
        String id = raw.substring("/api/files/".length());
        try { id = URLDecoder.decode(id, StandardCharsets.UTF_8); } catch (IllegalArgumentException ex) { reply(exchange, 400, "معرف الملف غير صالح."); return; }
        Path file = store.filePath(id); JsonObject item = store.message(id);
        if (file == null || item == null) { reply(exchange, 404, "الملف غير موجود."); return; }
        String mime = text(item, "mime", "application/octet-stream").toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        String name = text(item, "name", "shared-file").replaceAll("[\\r\\n\\\"]", "_");
        boolean preview = exchange.getRequestURI().getRawQuery() != null && exchange.getRequestURI().getRawQuery().contains("preview=1");
        boolean imagePreview = mime.matches("image/(png|jpeg|gif|webp|avif)");
        boolean streamingPreview = mime.matches("audio/(mpeg|ogg|wav|webm|mp4|aac|flac)|video/(mp4|webm|ogg|mpeg|quicktime)");
        boolean documentPreview = mime.equals("application/pdf") || mime.equals("text/plain");
        boolean officePreview = OfficePreviewService.supports(name);
        if (preview && !(imagePreview || streamingPreview || documentPreview || officePreview)) { reply(exchange, 415, "معاينة هذا النوع غير مدعومة."); return; }
        long size = Files.size(file);
        if (preview && imagePreview && size > MAX_PREVIEW_BYTES) { reply(exchange, 413, "حجم الصورة كبير للمعاينة؛ نزّلها لعرضها."); return; }
        if (preview && documentPreview && size > MAX_DOCUMENT_PREVIEW_BYTES) { reply(exchange, 413, "حجم المستند كبير للمعاينة؛ نزّله لعرضه."); return; }
        if (preview && officePreview && size > OfficePreviewService.MAX_INPUT_BYTES) { reply(exchange, 413, "حجم مستند Office يتجاوز حد المعاينة 32 ميغابايت."); return; }
        byte[] renderedOffice = null;
        if (preview && officePreview) {
            try { renderedOffice = OfficePreviewService.render(file, name).getBytes(StandardCharsets.UTF_8); }
            catch (IOException ex) { reply(exchange, 422, ex.getMessage() == null ? "تعذرت معاينة مستند Office." : ex.getMessage()); return; }
        }
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff"); headers.set("Accept-Ranges", "bytes"); headers.set("Connection", "close");
        headers.set("Access-Control-Allow-Origin", "http://127.0.0.1:43819");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Type", renderedOffice != null ? "text/html; charset=utf-8" : preview ? mime : "application/octet-stream");
        if (renderedOffice != null) headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors http://127.0.0.1:43819");
        headers.set("Content-Disposition", (preview ? "inline" : "attachment") + "; filename*=UTF-8''" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        if (renderedOffice != null) {
            headers.set("Content-Length", Integer.toString(renderedOffice.length));
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
            exchange.sendResponseHeaders(200, renderedOffice.length);
            try (var output = exchange.getResponseBody()) { output.write(renderedOffice); }
            return;
        }
        long start = 0, end = Math.max(0, size - 1); int status = 200;
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && (!preview || streamingPreview)) {
            long[] requested = parseRange(range, size);
            if (requested == null) { headers.set("Content-Range", "bytes */" + size); reply(exchange, 416, "نطاق التنزيل غير صالح."); return; }
            start = requested[0]; end = requested[1]; status = 206;
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        long length = size == 0 ? 0 : end - start + 1;
        headers.set("Content-Length", Long.toString(length));
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(status, -1); exchange.close(); return; }
        exchange.sendResponseHeaders(status, length);
        if (length == 0) { exchange.close(); return; }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
             var output = new BufferedOutputStream(exchange.getResponseBody(), STREAM_BUFFER_BYTES)) {
            channel.position(start); ByteBuffer buffer = ByteBuffer.allocate(STREAM_BUFFER_BYTES); long remaining = length;
            int consecutiveEmptyReads = 0;
            while (remaining > 0) {
                buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read < 0) break;
                if (read == 0) {
                    if (++consecutiveEmptyReads >= 16) throw new IOException("File stream made no progress.");
                    Thread.yield();
                    continue;
                }
                consecutiveEmptyReads = 0;
                output.write(buffer.array(), 0, read); remaining -= read;
            }
        } catch (IOException ex) {
            try { exchange.close(); } catch (Exception ignored) { }
            throw ex;
        }
    }

    private static long[] parseRange(String header, long size) {
        if (size <= 0 || header == null || !header.startsWith("bytes=") || header.indexOf(',') >= 0) return null;
        String[] parts = header.substring(6).split("-", -1); if (parts.length != 2 || parts[0].isBlank()) return null;
        try {
            long start = Long.parseLong(parts[0]); long end = parts[1].isBlank() ? size - 1 : Long.parseLong(parts[1]);
            if (start < 0 || end < start || start >= size) return null;
            return new long[]{start, Math.min(end, size - 1)};
        } catch (NumberFormatException ex) { return null; }
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
    private static String text(JsonObject object, String key, String fallback) { return LanDiscovery.text(object, key, fallback); }
    @Override public synchronized void close() { if (server != null) { server.stop(1); server = null; } }
}
