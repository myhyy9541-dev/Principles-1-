package com.wasel;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CoreServicesTest {
    @TempDir Path temp;
    private final List<RoomStore> openStores = new ArrayList<>();

    private RoomStore openStore(Path path) throws IOException {
        RoomStore store = new RoomStore(path); openStores.add(store); return store;
    }
    @AfterEach void closeStores() {
        for (RoomStore store : openStores) store.close();
        openStores.clear();
    }

    @Test void slf4jProviderIsInstalled() {
        assertEquals("org.slf4j.simple.SimpleLoggerFactory", LoggerFactory.getILoggerFactory().getClass().getName());
    }

    @Test void broadcastAddressUsesSubnetMask() {
        assertEquals("192.168.4.255", SettingsStore.broadcastAddress("192.168.4.27", "255.255.255.0"));
        assertEquals("10.1.255.255", SettingsStore.broadcastAddress("10.1.23.4", "255.255.0.0"));
        assertEquals("255.255.255.255", SettingsStore.broadcastAddress("bad", "mask"));
    }

    @Test void settingsAreNormalizedAndPersisted() throws Exception {
        var file = temp.resolve("settings.json");
        var settings = new SettingsStore.Settings(); settings.discoveryIntervalMs = 250; settings.theme = "malicious"; settings.networkInterface = "missing"; settings.autoConnect = false;
        var saved = SettingsStore.save(file, settings, List.of());
        assertEquals(SettingsStore.DEFAULT_DISCOVERY_INTERVAL, saved.discoveryIntervalMs);
        assertEquals("light", saved.theme); assertEquals("auto", saved.networkInterface); assertFalse(saved.autoConnect);
        var loaded = SettingsStore.load(file, List.of()); assertEquals(saved.discoveryIntervalMs, loaded.discoveryIntervalMs);
    }

    @Test void privateHistoryAndReadReceiptsAreScopedToRecipients() throws Exception {
        RoomStore store = openStore(temp.resolve("room"));
        store.rememberContact("client_sender_00000001", "سارة"); store.rememberContact("client_recipient_0000002", "علي"); store.rememberContact("client_other_000000003", "نور");
        JsonObject message = new JsonObject(); message.addProperty("id", UUID.randomUUID().toString()); message.addProperty("type", "text"); message.addProperty("text", "خاص");
        message.addProperty("senderClientId", "client_sender_00000001"); message.addProperty("channel", "direct"); message.addProperty("toId", "client_recipient_0000002");
        message.add("deliveredTo", new JsonArray()); message.add("readBy", new JsonArray()); store.addMessage(message);
        assertEquals(1, store.historyFor("client_sender_00000001").size());
        assertEquals(1, store.historyFor("client_recipient_0000002").size());
        assertEquals(0, store.historyFor("client_other_000000003").size());
        store.markDeliveredBatch(List.of(message.get("id").getAsString()), "client_recipient_0000002");
        store.markReadBatch(List.of(message.get("id").getAsString()), "client_recipient_0000002");
        JsonObject saved = store.historyFor("client_sender_00000001").get(0).getAsJsonObject();
        assertEquals(1, saved.getAsJsonArray("deliveredTo").size()); assertEquals(1, saved.getAsJsonArray("readBy").size());
        assertEquals(1, store.historyPageForChat("client_recipient_0000002", "user:client_sender_00000001", Long.MAX_VALUE, "\uffff", 200).size());
    }

    @Test void groupsRestrictVisibilityAndFileUploadsEnforceOwner() throws Exception {
        RoomStore store = openStore(temp.resolve("room"));
        String owner = "client_owner_0000000001", member = "client_member_00000002", other = "client_other_000000003";
        store.rememberContact(owner, "أحمد"); store.rememberContact(member, "هند"); store.rememberContact(other, "سعيد");
        JsonObject group = store.createGroup("الفريق", List.of(member), owner);
        String groupId = group.get("id").getAsString();
        assertTrue(store.isGroupMember(groupId, member)); assertFalse(store.isGroupMember(groupId, other));
        String transfer = UUID.randomUUID().toString(); byte[] bytes = "test-file".getBytes(StandardCharsets.UTF_8);
        JsonObject meta = new JsonObject(); meta.addProperty("transferId", transfer); meta.addProperty("name", "folder/test.txt"); meta.addProperty("mime", "text/plain"); meta.addProperty("size", bytes.length); meta.addProperty("channel", "direct"); meta.addProperty("groupId", groupId); meta.addProperty("user", "أحمد");
        assertEquals(0, store.beginUpload(meta, "socket-owner", owner));
        assertThrows(IllegalArgumentException.class, () -> store.appendChunk(transfer, 0, Base64.getEncoder().encodeToString(bytes), other));
        store.appendChunk(transfer, 0, Base64.getEncoder().encodeToString(bytes), owner);
        JsonObject file = store.completeUpload(transfer, owner);
        assertTrue(store.filePath(file.get("id").getAsString()).toFile().isFile());
        assertEquals(1, store.historyFor(member).size()); assertEquals(0, store.historyFor(other).size());
    }

    @Test void largeUploadChunkAndOneGigabyteFileLimitAreAccepted() throws Exception {
        RoomStore store = openStore(temp.resolve("large-room"));
        assertEquals(1024L * 1024 * 1024, RoomStore.MAX_FILE_BYTES);
        assertEquals(10L * 1024 * 1024 * 1024, RoomStore.MAX_STORAGE_BYTES);
        String sender = "client_large_sender_00001";
        byte[] bytes = new byte[RoomStore.MAX_CHUNK_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        String transfer = UUID.randomUUID().toString();
        JsonObject meta = new JsonObject(); meta.addProperty("transferId", transfer); meta.addProperty("name", "large-chunk.bin"); meta.addProperty("mime", "application/octet-stream"); meta.addProperty("size", bytes.length); meta.addProperty("channel", "general");
        assertEquals(0, store.beginUpload(meta, "large-socket", sender));
        assertEquals(bytes.length, store.appendChunk(transfer, 0, Base64.getEncoder().encodeToString(bytes), sender));
        JsonObject file = store.completeUpload(transfer, sender);
        assertEquals(bytes.length, java.nio.file.Files.size(store.filePath(file.get("id").getAsString())));

        String maximumTransfer = UUID.randomUUID().toString();
        JsonObject maximum = new JsonObject(); maximum.addProperty("transferId", maximumTransfer); maximum.addProperty("name", "large-file.bin"); maximum.addProperty("size", RoomStore.MAX_FILE_BYTES);
        assertEquals(0, store.beginUpload(maximum, "large-socket", sender));
        store.cancelUpload(maximumTransfer, sender);
    }

    @Test void oldMessagesWithoutChannelAreMigratedToGeneral() throws Exception {
        Path root = temp.resolve("room"); java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.writeString(root.resolve("history.json"), "[{\"id\":\"old\",\"type\":\"text\",\"text\":\"قديم\"}]");
        RoomStore store = openStore(root);
        assertEquals("general", store.historyFor("any-client").get(0).getAsJsonObject().get("channel").getAsString());
    }

    @Test void messageHistoryHasNoFiveThousandMessageCapAndLoadsInPages() throws Exception {
        Path root = temp.resolve("unbounded-room"); RoomStore store = openStore(root);
        List<JsonObject> batch = new java.util.ArrayList<>();
        for (int i = 1; i <= 5105; i++) {
            JsonObject message = new JsonObject(); message.addProperty("id", UUID.randomUUID().toString()); message.addProperty("type", "text");
            message.addProperty("text", "رسالة " + i); message.addProperty("channel", "general"); message.addProperty("time", i); batch.add(message);
        }
        store.addMessages(batch);
        assertEquals(5105, store.historyFor("reader").size());
        JsonArray latest = store.historyPageForChat("reader", "general", Long.MAX_VALUE, "\uffff", RoomStore.HISTORY_PAGE_SIZE);
        assertEquals(RoomStore.HISTORY_PAGE_SIZE, latest.size());
        long firstTime = latest.get(0).getAsJsonObject().get("time").getAsLong();
        assertEquals(4906, firstTime);
        JsonArray older = store.historyPageForChat("reader", "general", firstTime, latest.get(0).getAsJsonObject().get("id").getAsString(), RoomStore.HISTORY_PAGE_SIZE);
        assertEquals(RoomStore.HISTORY_PAGE_SIZE, older.size());
        assertTrue(older.get(older.size() - 1).getAsJsonObject().get("time").getAsLong() < firstTime);
        RoomStore reopened = openStore(root);
        assertEquals(5105, reopened.historyFor("reader").size());
    }

    @Test void fileServerStreamsRangesAndRefusesOversizedImagePreview() throws Exception {
        Path root = temp.resolve("file-http-room");
        try (RoomStore store = new RoomStore(root); FileServer server = new FileServer(store, 0)) {
            byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8); String owner = "client_http_sender_00001";
            String transfer = UUID.randomUUID().toString(); JsonObject meta = new JsonObject();
            meta.addProperty("transferId", transfer); meta.addProperty("name", "range-test.txt"); meta.addProperty("mime", "text/plain");
            meta.addProperty("size", content.length); meta.addProperty("channel", "general"); meta.addProperty("user", "مستخدم");
            store.beginUpload(meta, "test-socket", owner); store.appendChunk(transfer, 0, Base64.getEncoder().encodeToString(content), owner);
            JsonObject uploaded = store.completeUpload(transfer, owner); server.start();
            var client = java.net.http.HttpClient.newHttpClient();
            String url = "http://127.0.0.1:" + server.port() + "/api/files/" + uploaded.get("id").getAsString();
            var partial = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("Range", "bytes=2-5").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, partial.statusCode()); assertArrayEquals("2345".getBytes(StandardCharsets.UTF_8), partial.body());
            assertEquals("bytes 2-5/10", partial.headers().firstValue("Content-Range").orElse(""));
            var full = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, full.statusCode()); assertArrayEquals(content, full.body());

            String largeImageId = UUID.randomUUID().toString(); Path largeImage = root.resolve("files").resolve(largeImageId);
            java.nio.file.Files.write(largeImage, new byte[8 * 1024 * 1024 + 1]); JsonObject image = new JsonObject();
            image.addProperty("id", largeImageId); image.addProperty("type", "file"); image.addProperty("name", "large.png");
            image.addProperty("mime", "image/png"); image.addProperty("size", 8 * 1024 * 1024 + 1); image.addProperty("diskName", largeImageId);
            image.addProperty("channel", "general"); image.addProperty("time", System.currentTimeMillis()); store.addMessage(image);
            var preview = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url.substring(0, url.lastIndexOf('/') + 1) + largeImageId + "?preview=1")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(413, preview.statusCode());
        }
    }
}
