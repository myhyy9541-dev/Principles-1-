package com.wasel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomStore {
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_STORAGE_BYTES = 1024L * 1024 * 1024;
    public static final int MAX_HISTORY = 5000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path root, filesDir, historyPath, groupsPath, contactsPath;
    private final JsonArray history = new JsonArray(), groups = new JsonArray(), contacts = new JsonArray();
    private final Map<String, JsonObject> uploads = new ConcurrentHashMap<>();
    private long storedBytes;

    public RoomStore(Path root) throws IOException {
        this.root = root; filesDir = root.resolve("files"); historyPath = root.resolve("history.json");
        groupsPath = root.resolve("groups.json"); contactsPath = root.resolve("contacts.json");
        Files.createDirectories(filesDir);
        try (var stream = Files.list(filesDir)) { storedBytes = stream.filter(p -> !p.getFileName().toString().endsWith(".part")).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum(); }
        loadArray(historyPath, history);
        for (JsonElement element : history) if (element.isJsonObject()) {
            JsonObject message = element.getAsJsonObject();
            if (!message.has("channel")) message.addProperty("channel", "general");
        }
        trimHistory(); loadArray(groupsPath, groups); loadArray(contactsPath, contacts);
    }

    private static void loadArray(Path path, JsonArray into) {
        if (!Files.exists(path)) return;
        try { JsonElement data = JsonParser.parseString(Files.readString(path)); if (data.isJsonArray()) into.addAll(data.getAsJsonArray()); }
        catch (Exception ignored) { }
    }
    private static JsonObject copy(JsonObject item) { return GSON.fromJson(GSON.toJson(item), JsonObject.class); }
    private synchronized void persistArray(Path path, JsonArray data) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp"); Files.writeString(tmp, GSON.toJson(data));
        try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ex) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    private void trimHistory() { while (history.size() > MAX_HISTORY) history.remove(0); }
    public synchronized void addMessage(JsonObject message) throws IOException { history.add(copy(message)); trimHistory(); persistArray(historyPath, history); }
    public synchronized void rememberContact(String id, String name) throws IOException {
        for (JsonElement entry : contacts) if (id.equals(entry.getAsJsonObject().get("clientId").getAsString())) {
            entry.getAsJsonObject().addProperty("name", name); entry.getAsJsonObject().addProperty("lastSeen", System.currentTimeMillis()); persistArray(contactsPath, contacts); return;
        }
        JsonObject item = new JsonObject(); item.addProperty("clientId", id); item.addProperty("name", name); item.addProperty("lastSeen", System.currentTimeMillis()); contacts.add(item); persistArray(contactsPath, contacts);
    }
    public synchronized JsonArray contacts() { JsonArray out = new JsonArray(); contacts.forEach(c -> out.add(copy(c.getAsJsonObject()))); return out; }
    public synchronized boolean hasContact(String id) { for (JsonElement e : contacts) if (id.equals(e.getAsJsonObject().get("clientId").getAsString())) return true; return false; }
    public synchronized JsonObject contact(String id) { for (JsonElement e : contacts) if (id.equals(e.getAsJsonObject().get("clientId").getAsString())) return copy(e.getAsJsonObject()); return null; }

    private synchronized JsonObject group(String id) { for (JsonElement e : groups) if (id.equals(e.getAsJsonObject().get("id").getAsString())) return e.getAsJsonObject(); return null; }
    public synchronized boolean isGroupMember(String groupId, String clientId) { JsonObject g = group(groupId); return g != null && strings(g.getAsJsonArray("members")).contains(clientId); }
    public synchronized List<String> groupMembers(String groupId) { JsonObject g = group(groupId); return g == null ? List.of() : strings(g.getAsJsonArray("members")); }
    public synchronized JsonArray visibleGroups(String clientId) {
        JsonArray out = new JsonArray();
        for (JsonElement item : groups) { JsonObject g = item.getAsJsonObject(); if (strings(g.getAsJsonArray("members")).contains(clientId)) out.add(copy(g)); }
        return out;
    }
    public synchronized JsonObject createGroup(String name, List<String> memberIds, String ownerId) throws IOException {
        String cleanName = name == null ? "" : name.strip().replaceAll("[\\r\\n\\u0000]", " ");
        if (cleanName.isEmpty()) throw new IllegalArgumentException("اكتب اسماً للمجموعة.");
        cleanName = cleanName.substring(0, Math.min(60, cleanName.length()));
        List<String> unique = new ArrayList<>(); unique.add(ownerId);
        for (String id : memberIds) if (id != null && !id.equals(ownerId) && hasContact(id) && !unique.contains(id)) unique.add(id);
        if (unique.size() < 2) throw new IllegalArgumentException("أضف عضواً آخر معروفاً للمضيف على الأقل.");
        JsonObject group = new JsonObject(); group.addProperty("id", UUID.randomUUID().toString()); group.addProperty("name", cleanName); group.addProperty("ownerId", ownerId); group.addProperty("createdAt", System.currentTimeMillis());
        JsonArray members = new JsonArray(); unique.forEach(members::add); group.add("members", members); groups.add(group); persistArray(groupsPath, groups); return copy(group);
    }

    private synchronized JsonObject findMessage(String id) { for (JsonElement e : history) if (id.equals(e.getAsJsonObject().get("id").getAsString())) return e.getAsJsonObject(); return null; }
    private List<String> recipients(JsonObject message) {
        if (message.has("recipientIds") && message.get("recipientIds").isJsonArray()) return strings(message.getAsJsonArray("recipientIds"));
        if (message.has("toId") && !message.get("toId").isJsonNull()) return List.of(message.get("toId").getAsString());
        if (message.has("groupId") && !message.get("groupId").isJsonNull()) { JsonObject group = group(message.get("groupId").getAsString()); return group == null ? List.of() : strings(group.getAsJsonArray("members")); }
        return List.of();
    }
    public synchronized JsonArray historyFor(String clientId) {
        JsonArray out = new JsonArray();
        for (JsonElement entry : history) {
            JsonObject message = entry.getAsJsonObject();
            String channel = string(message, "channel", "general");
            if ("general".equals(channel) || clientId.equals(string(message, "senderClientId", "")) || recipients(message).contains(clientId)) {
                JsonObject safe = copy(message); safe.remove("diskName"); safe.remove("recipientIds"); out.add(safe);
            }
        }
        return out;
    }
    public synchronized List<JsonObject> offlineFor(String clientId) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : history) {
            JsonObject m = e.getAsJsonObject(); JsonArray delivered = m.has("deliveredTo") && m.get("deliveredTo").isJsonArray() ? m.getAsJsonArray("deliveredTo") : new JsonArray();
            if (!"general".equals(string(m, "channel", "general")) && !clientId.equals(string(m, "senderClientId", "")) && recipients(m).contains(clientId) && !strings(delivered).contains(clientId)) out.add(copy(m));
        }
        return out;
    }
    public synchronized JsonObject markDelivered(String id, String clientId) throws IOException { return addReceipt(id, clientId, "deliveredTo"); }
    public synchronized JsonObject markRead(String id, String clientId) throws IOException { return addReceipt(id, clientId, "readBy"); }
    private JsonObject addReceipt(String id, String clientId, String key) throws IOException {
        JsonObject message = findMessage(id); if (message == null || !recipients(message).contains(clientId)) return null;
        JsonArray list = message.has(key) && message.get(key).isJsonArray() ? message.getAsJsonArray(key) : new JsonArray();
        if (!strings(list).contains(clientId)) list.add(clientId); message.add(key, list); persistArray(historyPath, history); return copy(message);
    }

    public synchronized long beginUpload(JsonObject input, String senderId, String senderClientId) throws IOException {
        String transferId = string(input, "transferId", "");
        if (!transferId.matches("(?i)[0-9a-f-]{36}")) throw new IllegalArgumentException("معرف النقل غير صالح.");
        long size = input.has("size") ? input.get("size").getAsLong() : -1;
        if (size < 1 || size > MAX_FILE_BYTES) throw new IllegalArgumentException("حجم الملف يجب ألا يتجاوز 10 ميغابايت.");
        Path part = filesDir.resolve(transferId + ".part");
        JsonObject old = uploads.get(transferId);
        if (old != null && !senderClientId.equals(string(old, "senderClientId", ""))) throw new IllegalArgumentException("معرف النقل مستخدم من عميل آخر.");
        if (!Files.exists(part)) Files.createFile(part);
        long offset = Files.size(part); if (offset > size) { Files.newByteChannel(part, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).close(); offset = 0; }
        JsonObject meta = copy(input); meta.addProperty("transferId", transferId); meta.addProperty("size", size); meta.addProperty("name", safeRelativeName(string(input, "name", "ملف")));
        meta.addProperty("senderId", senderId); meta.addProperty("senderClientId", senderClientId); meta.addProperty("offset", offset); uploads.put(transferId, meta); return offset;
    }
    public synchronized long appendChunk(String transferId, long offset, String base64, String senderClientId) throws IOException {
        JsonObject meta = uploads.get(transferId); if (meta == null) throw new IllegalArgumentException("جلسة رفع الملف غير موجودة.");
        if (!senderClientId.equals(string(meta, "senderClientId", ""))) throw new IllegalArgumentException("لا تملك صلاحية تعديل النقل.");
        byte[] bytes; try { bytes = Base64.getDecoder().decode(base64); } catch (Exception ex) { throw new IllegalArgumentException("مقطع الملف غير صالح."); }
        long current = meta.get("offset").getAsLong(), size = meta.get("size").getAsLong();
        if (bytes.length < 1 || bytes.length > 128 * 1024 || offset != current || current + bytes.length > size) throw new IllegalArgumentException("ترتيب مقاطع الملف غير صحيح.");
        try (var channel = java.nio.channels.FileChannel.open(filesDir.resolve(transferId + ".part"), java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(current); channel.write(java.nio.ByteBuffer.wrap(bytes)); channel.force(false);
        }
        meta.addProperty("offset", current + bytes.length); return current + bytes.length;
    }
    public synchronized JsonObject completeUpload(String transferId, String senderClientId) throws IOException {
        JsonObject meta = uploads.get(transferId);
        if (meta == null || meta.get("offset").getAsLong() != meta.get("size").getAsLong()) throw new IllegalArgumentException("لم يكتمل رفع الملف بعد.");
        if (!senderClientId.equals(string(meta, "senderClientId", ""))) throw new IllegalArgumentException("لا تملك صلاحية إكمال النقل.");
        long size = meta.get("size").getAsLong(); if (storedBytes + size > MAX_STORAGE_BYTES) throw new IllegalArgumentException("امتلأت مساحة الملفات على المضيف.");
        String id = UUID.randomUUID().toString(); Path file = filesDir.resolve(id); Files.move(filesDir.resolve(transferId + ".part"), file, StandardCopyOption.REPLACE_EXISTING);
        JsonObject record = new JsonObject(); record.addProperty("id", id); record.addProperty("type", "file"); record.addProperty("name", string(meta, "name", "ملف"));
        record.addProperty("mime", string(meta, "mime", "application/octet-stream")); record.addProperty("size", size); record.addProperty("user", string(meta, "user", "مستخدم"));
        record.addProperty("senderId", string(meta, "senderId", "")); record.addProperty("senderClientId", senderClientId); record.addProperty("channel", string(meta, "channel", "general"));
        copyField(meta, record, "toId"); copyField(meta, record, "groupId"); record.addProperty("time", System.currentTimeMillis()); record.addProperty("diskName", id);
        record.add("deliveredTo", new JsonArray()); record.add("readBy", new JsonArray());
        try { addMessage(record); } catch (IOException ex) { Files.move(file, filesDir.resolve(transferId + ".part"), StandardCopyOption.REPLACE_EXISTING); throw ex; }
        uploads.remove(transferId); storedBytes += size; return copy(record);
    }
    public synchronized void cancelUpload(String transferId, String senderClientId) throws IOException {
        JsonObject meta = uploads.get(transferId); if (meta != null && !senderClientId.equals(string(meta, "senderClientId", ""))) return;
        uploads.remove(transferId); Files.deleteIfExists(filesDir.resolve(transferId + ".part"));
    }
    public synchronized Path filePath(String id) {
        if (id == null || !id.matches("(?i)[0-9a-f-]{36}")) return null;
        JsonObject message = findMessage(id); if (message == null || !"file".equals(string(message, "type", ""))) return null;
        Path path = filesDir.resolve(string(message, "diskName", id)); return Files.isRegularFile(path) ? path : null;
    }
    public synchronized JsonObject message(String id) { JsonObject result = findMessage(id); return result == null ? null : copy(result); }
    public long storedBytes() { return storedBytes; }

    private static String safeRelativeName(String value) {
        String normalized = value.replace('\\', '/'); List<String> clean = new ArrayList<>();
        for (String part : normalized.split("/")) if (!part.isBlank() && !".".equals(part) && !"..".equals(part)) clean.add(part.replaceAll("[\\r\\n\\u0000]", "_").substring(0, Math.min(100, part.length())));
        String out = String.join("/", clean); return out.isBlank() ? "ملف" : out.substring(0, Math.min(180, out.length()));
    }
    private static void copyField(JsonObject src, JsonObject dst, String name) { if (src.has(name) && !src.get(name).isJsonNull()) dst.add(name, src.get(name).deepCopy()); }
    private static String string(JsonObject item, String key, String fallback) { return item.has(key) && !item.get(key).isJsonNull() ? item.get(key).getAsString() : fallback; }
    private static List<String> strings(JsonArray array) { List<String> out = new ArrayList<>(); if (array != null) for (JsonElement e : array) if (e.isJsonPrimitive()) out.add(e.getAsString()); return out; }
}
