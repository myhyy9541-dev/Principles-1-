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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomStore implements AutoCloseable {
    public static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    public static final long MAX_STORAGE_BYTES = 10L * 1024 * 1024 * 1024;
    public static final int MAX_CHUNK_BYTES = 1024 * 1024;
    public static final int HISTORY_PAGE_SIZE = 200;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path root, filesDir, historyPath, groupsPath, contactsPath;
    private final JsonArray groups = new JsonArray(), contacts = new JsonArray();
    private final Map<String, JsonObject> uploads = new ConcurrentHashMap<>();
    private final Connection historyDb;
    private long storedBytes;

    public RoomStore(Path root) throws IOException {
        this.root = root;
        filesDir = root.resolve("files"); historyPath = root.resolve("history.json");
        groupsPath = root.resolve("groups.json"); contactsPath = root.resolve("contacts.json");
        Files.createDirectories(filesDir);
        try (var stream = Files.list(filesDir)) {
            storedBytes = stream.filter(p -> !p.getFileName().toString().endsWith(".part"))
                    .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        }
        loadArray(groupsPath, groups); loadArray(contactsPath, contacts);
        try {
            Class.forName("org.sqlite.JDBC");
            historyDb = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("history.db").toAbsolutePath());
            initializeHistoryDatabase();
            migrateLegacyHistory();
        } catch (ClassNotFoundException | SQLException ex) {
            throw new IOException("تعذر فتح قاعدة سجل الرسائل المحلية.", ex);
        }
    }

    private void initializeHistoryDatabase() throws SQLException {
        try (Statement statement = historyDb.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, time INTEGER NOT NULL, channel TEXT NOT NULL, sender_client_id TEXT NOT NULL, to_id TEXT, group_id TEXT, payload TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS message_recipients (message_id TEXT NOT NULL, client_id TEXT NOT NULL, PRIMARY KEY(message_id, client_id), FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_time ON messages(time, id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_sender_time ON messages(sender_client_id, time, id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_recipient ON message_recipients(client_id, message_id)");
        }
    }

    private void migrateLegacyHistory() throws SQLException, IOException {
        long count;
        try (Statement statement = historyDb.createStatement(); ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM messages")) { count = row.next() ? row.getLong(1) : 0; }
        if (count != 0 || !Files.isRegularFile(historyPath)) return;
        JsonElement root;
        try { root = JsonParser.parseString(Files.readString(historyPath)); }
        catch (Exception ex) { throw new IOException("تعذر قراءة سجل الرسائل السابق؛ لم يتم حذفه أو استبداله.", ex); }
        if (!root.isJsonArray()) return;
        boolean previous = historyDb.getAutoCommit(); historyDb.setAutoCommit(false);
        try {
            for (JsonElement element : root.getAsJsonArray()) if (element.isJsonObject()) {
                JsonObject message = copy(element.getAsJsonObject());
                if (!message.has("channel")) message.addProperty("channel", "general");
                if (!message.has("id") || message.get("id").isJsonNull()) message.addProperty("id", UUID.randomUUID().toString());
                insertMessage(message);
            }
            historyDb.commit();
            Path migrated = historyPath.resolveSibling("history.json.migrated");
            if (!Files.exists(migrated)) try { Files.copy(historyPath, migrated); } catch (IOException ignored) { }
        } catch (SQLException | RuntimeException ex) {
            historyDb.rollback();
            throw ex;
        } finally { historyDb.setAutoCommit(previous); }
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

    public synchronized void addMessage(JsonObject message) throws IOException {
        addMessages(List.of(message));
    }
    public synchronized void addMessages(List<JsonObject> messages) throws IOException {
        boolean previous;
        try {
            previous = historyDb.getAutoCommit(); historyDb.setAutoCommit(false);
            try {
                for (JsonObject message : messages) insertMessage(copy(message));
                historyDb.commit();
            } catch (SQLException | RuntimeException ex) { historyDb.rollback(); throw ex; }
            finally { historyDb.setAutoCommit(previous); }
        } catch (SQLException ex) { throw new IOException("تعذر حفظ الرسائل في سجل واصل.", ex); }
    }

    private void insertMessage(JsonObject message) throws SQLException {
        if (!message.has("channel")) message.addProperty("channel", "general");
        if (!message.has("id") || message.get("id").isJsonNull()) message.addProperty("id", UUID.randomUUID().toString());
        if (!message.has("time") || message.get("time").isJsonNull()) message.addProperty("time", System.currentTimeMillis());
        String id = string(message, "id", "");
        String channel = string(message, "channel", "general");
        String sender = string(message, "senderClientId", "");
        String toId = string(message, "toId", "");
        String groupId = string(message, "groupId", "");
        try (PreparedStatement insert = historyDb.prepareStatement("INSERT OR REPLACE INTO messages(id,time,channel,sender_client_id,to_id,group_id,payload) VALUES(?,?,?,?,?,?,?)")) {
            insert.setString(1, id); insert.setLong(2, message.get("time").getAsLong()); insert.setString(3, channel);
            insert.setString(4, sender); insert.setString(5, toId); insert.setString(6, groupId); insert.setString(7, GSON.toJson(message)); insert.executeUpdate();
        }
        try (PreparedStatement delete = historyDb.prepareStatement("DELETE FROM message_recipients WHERE message_id=?")) { delete.setString(1, id); delete.executeUpdate(); }
        List<String> recipientIds = recipients(message);
        try (PreparedStatement insertRecipient = historyDb.prepareStatement("INSERT OR IGNORE INTO message_recipients(message_id,client_id) VALUES(?,?)")) {
            for (String recipient : recipientIds) if (recipient != null && !recipient.isBlank()) { insertRecipient.setString(1, id); insertRecipient.setString(2, recipient); insertRecipient.addBatch(); }
            insertRecipient.executeBatch();
        }
    }

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

    private List<String> recipients(JsonObject message) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (message.has("recipientIds") && message.get("recipientIds").isJsonArray()) out.addAll(strings(message.getAsJsonArray("recipientIds")));
        else if (message.has("toId") && !message.get("toId").isJsonNull()) out.add(message.get("toId").getAsString());
        else if (message.has("groupId") && !message.get("groupId").isJsonNull()) out.addAll(groupMembers(message.get("groupId").getAsString()));
        return new ArrayList<>(out);
    }

    private String visibleHistorySql(boolean paged) {
        return "SELECT m.payload FROM messages m WHERE (m.channel='general' OR m.sender_client_id=? OR EXISTS (SELECT 1 FROM message_recipients r WHERE r.message_id=m.id AND r.client_id=?))"
                + (paged ? " AND (m.time < ? OR (m.time = ? AND m.id < ?))" : "") + " ORDER BY m.time DESC, m.id DESC" + (paged ? " LIMIT ?" : "");
    }
    public synchronized JsonArray historyFor(String clientId) {
        try { return queryHistory(clientId, null, "", 0, false); }
        catch (SQLException ex) { throw new IllegalStateException("تعذر قراءة سجل المحادثات.", ex); }
    }
    public synchronized JsonArray historyPageFor(String clientId, long beforeTime, String beforeId, int limit) {
        try { return queryHistory(clientId, beforeTime, beforeId, Math.max(1, Math.min(limit, 1000)), true); }
        catch (SQLException ex) { throw new IllegalStateException("تعذر قراءة صفحة سجل المحادثات.", ex); }
    }
    public synchronized JsonArray historyPageForChat(String clientId, String chat, long beforeTime, String beforeId, int limit) {
        int pageSize = Math.max(1, Math.min(limit, 1000));
        String filter; List<String> parameters = new ArrayList<>();
        if ("general".equals(chat)) filter = "m.channel IN ('general','broadcast')";
        else if (chat != null && chat.startsWith("user:")) {
            String other = chat.substring(5);
            if (!other.matches("[A-Za-z0-9_-]{16,80}") || other.equals(clientId)) return new JsonArray();
            filter = "m.channel='direct' AND ((m.sender_client_id=? AND m.to_id=?) OR (m.sender_client_id=? AND m.to_id=?))";
            parameters.add(clientId); parameters.add(other); parameters.add(other); parameters.add(clientId);
        } else if (chat != null && chat.startsWith("group:")) {
            String groupId = chat.substring(6);
            if (!isGroupMember(groupId, clientId)) return new JsonArray();
            filter = "m.group_id=?"; parameters.add(groupId);
        } else return new JsonArray();
        String sql = "SELECT m.payload FROM messages m WHERE " + filter + " AND (m.time < ? OR (m.time = ? AND m.id < ?)) ORDER BY m.time DESC,m.id DESC LIMIT ?";
        JsonArray out = new JsonArray();
        try (PreparedStatement query = historyDb.prepareStatement(sql)) {
            int index = 1; for (String parameter : parameters) query.setString(index++, parameter);
            query.setLong(index++, beforeTime); query.setLong(index++, beforeTime); query.setString(index++, beforeId == null ? "\uffff" : beforeId); query.setInt(index, pageSize);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) { JsonObject message = JsonParser.parseString(rows.getString(1)).getAsJsonObject(); message.remove("diskName"); message.remove("recipientIds"); out.add(message); }
            }
        } catch (SQLException ex) { throw new IllegalStateException("تعذر تحميل رسائل هذه المحادثة.", ex); }
        reverse(out); return out;
    }
    private JsonArray queryHistory(String clientId, Long beforeTime, String beforeId, int limit, boolean paged) throws SQLException {
        JsonArray out = new JsonArray();
        try (PreparedStatement query = historyDb.prepareStatement(visibleHistorySql(paged))) {
            query.setString(1, clientId); query.setString(2, clientId);
            if (paged) { query.setLong(3, beforeTime == null ? Long.MAX_VALUE : beforeTime); query.setLong(4, beforeTime == null ? Long.MAX_VALUE : beforeTime); query.setString(5, beforeId == null ? "\uffff" : beforeId); query.setInt(6, limit); }
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    JsonObject message = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                    message.remove("diskName"); message.remove("recipientIds"); out.add(message);
                }
            }
        }
        reverse(out); return out;
    }
    private static void reverse(JsonArray array) {
        List<JsonElement> items = new ArrayList<>(); array.forEach(items::add);
        for (int i = array.size() - 1; i >= 0; i--) array.remove(i);
        for (int i = items.size() - 1; i >= 0; i--) array.add(items.get(i));
    }

    public synchronized List<JsonObject> offlineFor(String clientId) {
        List<JsonObject> out = new ArrayList<>();
        String sql = "SELECT m.payload FROM messages m JOIN message_recipients r ON r.message_id=m.id WHERE m.channel<>'general' AND r.client_id=? ORDER BY m.time,m.id";
        try (PreparedStatement query = historyDb.prepareStatement(sql)) {
            query.setString(1, clientId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    JsonObject message = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                    JsonArray delivered = message.has("deliveredTo") && message.get("deliveredTo").isJsonArray() ? message.getAsJsonArray("deliveredTo") : new JsonArray();
                    if (!strings(delivered).contains(clientId)) out.add(message);
                }
            }
        } catch (SQLException ex) { throw new IllegalStateException("تعذر قراءة الرسائل غير المسلّمة.", ex); }
        return out;
    }

    public synchronized JsonObject markDelivered(String id, String clientId) throws IOException { return addReceipt(id, clientId, "deliveredTo"); }
    public synchronized List<JsonObject> markDeliveredBatch(List<String> ids, String clientId) throws IOException { return updateReceipts(ids, clientId, "deliveredTo"); }
    public synchronized JsonObject markRead(String id, String clientId) throws IOException { List<JsonObject> changed = updateReceipts(List.of(id), clientId, "readBy"); return changed.isEmpty() ? message(id) : changed.get(0); }
    public synchronized List<JsonObject> markReadBatch(List<String> ids, String clientId) throws IOException { return updateReceipts(ids, clientId, "readBy"); }
    private JsonObject addReceipt(String id, String clientId, String key) throws IOException { List<JsonObject> changed = updateReceipts(List.of(id), clientId, key); return changed.isEmpty() ? message(id) : changed.get(0); }
    private List<JsonObject> updateReceipts(List<String> ids, String clientId, String key) throws IOException {
        List<JsonObject> changed = new ArrayList<>(); boolean previous;
        try {
            previous = historyDb.getAutoCommit(); historyDb.setAutoCommit(false);
            try (PreparedStatement select = historyDb.prepareStatement("SELECT payload FROM messages WHERE id=? AND EXISTS (SELECT 1 FROM message_recipients r WHERE r.message_id=messages.id AND r.client_id=?)");
                 PreparedStatement update = historyDb.prepareStatement("UPDATE messages SET payload=? WHERE id=?")) {
                for (String id : new LinkedHashSet<>(ids)) {
                    select.setString(1, id); select.setString(2, clientId);
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) continue;
                        JsonObject message = JsonParser.parseString(row.getString(1)).getAsJsonObject();
                        JsonArray recipients = message.has(key) && message.get(key).isJsonArray() ? message.getAsJsonArray(key) : new JsonArray();
                        if (strings(recipients).contains(clientId)) continue;
                        recipients.add(clientId); message.add(key, recipients);
                        update.setString(1, GSON.toJson(message)); update.setString(2, id); update.addBatch(); changed.add(copy(message));
                    }
                }
                update.executeBatch(); historyDb.commit();
            } catch (SQLException | RuntimeException ex) { historyDb.rollback(); throw ex; }
            finally { historyDb.setAutoCommit(previous); }
            return changed;
        } catch (SQLException ex) { throw new IOException("تعذر حفظ إيصالات الرسائل.", ex); }
    }

    public synchronized long beginUpload(JsonObject input, String senderId, String senderClientId) throws IOException {
        String transferId = string(input, "transferId", "");
        if (!transferId.matches("(?i)[0-9a-f-]{36}")) throw new IllegalArgumentException("معرف النقل غير صالح.");
        long size = input.has("size") ? input.get("size").getAsLong() : -1;
        if (size < 1 || size > MAX_FILE_BYTES) throw new IllegalArgumentException("حجم الملف يجب ألا يتجاوز 1 غيغابايت.");
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
        if (bytes.length < 1 || bytes.length > MAX_CHUNK_BYTES || offset != current || current + bytes.length > size) throw new IllegalArgumentException("ترتيب مقاطع الملف غير صحيح.");
        try (var channel = java.nio.channels.FileChannel.open(filesDir.resolve(transferId + ".part"), java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(current); var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
        }
        meta.addProperty("offset", current + bytes.length); return current + bytes.length;
    }
    public synchronized JsonObject completeUpload(String transferId, String senderClientId) throws IOException {
        JsonObject meta = uploads.get(transferId);
        if (meta == null || meta.get("offset").getAsLong() != meta.get("size").getAsLong()) throw new IllegalArgumentException("لم يكتمل رفع الملف بعد.");
        if (!senderClientId.equals(string(meta, "senderClientId", ""))) throw new IllegalArgumentException("لا تملك صلاحية إكمال النقل.");
        long size = meta.get("size").getAsLong(); if (storedBytes + size > MAX_STORAGE_BYTES) throw new IllegalArgumentException("امتلأت مساحة الملفات المحددة (10 غيغابايت) على هذا الجهاز.");
        Path part = filesDir.resolve(transferId + ".part");
        try (var channel = java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
        String id = UUID.randomUUID().toString(); Path file = filesDir.resolve(id); Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
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
        JsonObject item = message(id); if (item == null || !"file".equals(string(item, "type", ""))) return null;
        Path path = filesDir.resolve(string(item, "diskName", id)); return Files.isRegularFile(path) ? path : null;
    }
    public synchronized JsonObject message(String id) {
        try (PreparedStatement query = historyDb.prepareStatement("SELECT payload FROM messages WHERE id=?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) { return row.next() ? JsonParser.parseString(row.getString(1)).getAsJsonObject() : null; }
        } catch (SQLException ex) { throw new IllegalStateException("تعذر قراءة بيانات الرسالة.", ex); }
    }
    public long storedBytes() { return storedBytes; }

    private static String safeRelativeName(String value) {
        String normalized = value.replace('\\', '/'); List<String> clean = new ArrayList<>();
        for (String part : normalized.split("/")) if (!part.isBlank() && !".".equals(part) && !"..".equals(part)) clean.add(part.replaceAll("[\\r\\n\\u0000]", "_").substring(0, Math.min(100, part.length())));
        String out = String.join("/", clean); return out.isBlank() ? "ملف" : out.substring(0, Math.min(180, out.length()));
    }
    private static void copyField(JsonObject src, JsonObject dst, String name) { if (src.has(name) && !src.get(name).isJsonNull()) dst.add(name, src.get(name).deepCopy()); }
    private static String string(JsonObject item, String key, String fallback) { return item.has(key) && !item.get(key).isJsonNull() ? item.get(key).getAsString() : fallback; }
    private static List<String> strings(JsonArray array) { List<String> out = new ArrayList<>(); if (array != null) for (JsonElement e : array) if (e.isJsonPrimitive()) out.add(e.getAsString()); return out; }

    @Override public synchronized void close() { try { historyDb.close(); } catch (SQLException ignored) { } }
}
