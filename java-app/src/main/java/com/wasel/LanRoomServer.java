package com.wasel;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class LanRoomServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGE_CHARS = 5000;
    private final RoomStore store;
    private final Consumer<String> notice;
    private final Supplier<JsonArray> sharedPrinters;
    private final int port;
    private final Map<UUID, JsonObject> peers = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> rates = new ConcurrentHashMap<>();
    private volatile SocketIOServer server;
    private final Map<String, JsonObject> activeCalls = new ConcurrentHashMap<>();
    private boolean closing;

    public LanRoomServer(RoomStore store, Consumer<String> notice) { this(store, notice, LanDiscovery.SOCKET_PORT, JsonArray::new); }
    public LanRoomServer(RoomStore store, Consumer<String> notice, int port) { this(store, notice, port, JsonArray::new); }
    public LanRoomServer(RoomStore store, Consumer<String> notice, int port, Supplier<JsonArray> sharedPrinters) { this.store = store; this.notice = notice; this.port = port; this.sharedPrinters = sharedPrinters; }

    public synchronized void start() {
        if (server != null || closing) return;
        Configuration config = new Configuration(); config.setHostname("0.0.0.0"); config.setPort(port);
        config.setMaxHttpContentLength(2 * 1024 * 1024); config.setMaxFramePayloadLength(2 * 1024 * 1024);
        config.setEnableCors(true); config.setOrigin("*"); config.setPingInterval(25000); config.setPingTimeout(60000);
        SocketIOServer next = new SocketIOServer(config);
        next.addConnectListener(this::onConnect); next.addDisconnectListener(this::onDisconnect);
        next.addEventListener("join", Map.class, (client, data, ack) -> join(client, json(data)));
        next.addEventListener("history-before", Map.class, (client, data, ack) -> historyBefore(client, json(data)));
        next.addEventListener("history-chat", Map.class, (client, data, ack) -> historyChat(client, json(data)));
        next.addEventListener("chat", Map.class, (client, data, ack) -> chat(client, json(data)));
        next.addEventListener("broadcast", Map.class, (client, data, ack) -> broadcast(client, json(data)));
        next.addEventListener("create-group", Map.class, (client, data, ack) -> createGroup(client, json(data)));
        next.addEventListener("update-profile", Map.class, (client, data, ack) -> updateProfile(client, json(data)));
        next.addEventListener("mark-read", Map.class, (client, data, ack) -> markRead(client, json(data)));
        next.addEventListener("upload-init", Map.class, (client, data, ack) -> uploadInit(client, json(data), ack));
        next.addEventListener("upload-chunk", Map.class, (client, data, ack) -> uploadChunk(client, json(data), ack));
        next.addEventListener("upload-complete", Map.class, (client, data, ack) -> uploadComplete(client, json(data), ack));
        next.addEventListener("upload-cancel", Map.class, (client, data, ack) -> uploadCancel(client, json(data)));
        next.addEventListener("signal", Map.class, (client, data, ack) -> signal(client, json(data)));
        next.addEventListener("print-list-request", Map.class, (client, data, ack) -> printListRequest(client, json(data)));
        next.addEventListener("print-list", Map.class, (client, data, ack) -> printListResponse(client, json(data)));
        next.addEventListener("print-offer", Map.class, (client, data, ack) -> printOffer(client, json(data)));
        next.addEventListener("print-result", Map.class, (client, data, ack) -> printResult(client, json(data)));
        next.start(); server = next;
    }

    private void onConnect(SocketIOClient client) { }
    private void join(SocketIOClient client, JsonObject raw) {
        if (peers.containsKey(client.getSessionId())) return;
        String clientId = text(raw, "clientId", "");
        if (!clientId.matches("[A-Za-z0-9_-]{16,80}")) { send(client, "notice", "تعذرت قراءة هوية هذا الجهاز؛ أعد تشغيل التطبيق."); return; }
        String windowId = text(raw, "windowId", "");
        if (!windowId.isEmpty() && !windowId.matches("(?i)([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{1,64})")) { send(client, "notice", "معرّف النافذة غير صالح."); return; }
        String windowRole = text(raw, "windowRole", "monitor");
        if (!Set.of("monitor", "workspace", "call").contains(windowRole)) { send(client, "notice", "دور النافذة غير صالح."); return; }
        String name = cleanName(text(raw, "name", "مستخدم"));
        boolean firstWindow = clientsForClient(clientId).isEmpty();
        JsonObject user = new JsonObject(); user.addProperty("id", client.getSessionId().toString()); user.addProperty("clientId", clientId); user.addProperty("name", name); user.addProperty("joinedAt", System.currentTimeMillis());
        if (!windowId.isEmpty()) user.addProperty("windowId", windowId); user.addProperty("windowRole", windowRole);
        peers.put(client.getSessionId(), user);
        try { store.rememberContact(clientId, name); }
        catch (IOException ex) { send(client, "notice", "تعذر حفظ جهة الاتصال: " + ex.getMessage()); }
        send(client, "identity", obj("clientId", clientId)); send(client, "history", store.historyPageFor(clientId, Long.MAX_VALUE, "", RoomStore.HISTORY_PAGE_SIZE));
        send(client, "groups", store.visibleGroups(clientId)); send(client, "contacts", contactList());
        if (firstWindow) {
            broadcastContacts(); broadcastMembers();
            JsonObject system = new JsonObject(); system.addProperty("text", name + " انضم إلى الغرفة"); system.addProperty("time", System.currentTimeMillis()); broadcast("system-message", system);
        }
    }

    private void historyBefore(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        try {
            long beforeTime = raw.has("beforeTime") && raw.get("beforeTime").isJsonPrimitive() ? raw.get("beforeTime").getAsLong() : Long.MAX_VALUE;
            String beforeId = text(raw, "beforeId", ""), chat = text(raw, "chat", "general");
            JsonArray page = store.historyPageForChat(text(user, "clientId", ""), chat, beforeTime, beforeId, RoomStore.HISTORY_PAGE_SIZE);
            send(client, "history-older", historyPacket(chat, page)); notifyPageDelivered(page, text(user, "clientId", ""));
        } catch (Exception ex) { send(client, "notice", "تعذر تحميل الرسائل الأقدم."); }
    }

    private void historyChat(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        try {
            String chat = text(raw, "chat", "general");
            JsonArray page = store.historyPageForChat(text(user, "clientId", ""), chat, Long.MAX_VALUE, "", RoomStore.HISTORY_PAGE_SIZE);
            send(client, "history-chat-page", historyPacket(chat, page)); notifyPageDelivered(page, text(user, "clientId", ""));
        } catch (Exception ex) { send(client, "notice", "تعذر تحميل سجل المحادثة."); }
    }

    private static JsonObject historyPacket(String chat, JsonArray messages) {
        JsonObject packet = new JsonObject(); packet.addProperty("chat", chat); packet.add("messages", messages); return packet;
    }

    private void notifyPageDelivered(JsonArray page, String clientId) throws IOException {
        List<String> ids = new ArrayList<>(); for (JsonElement element : page) if (element.isJsonObject()) ids.add(text(element.getAsJsonObject(), "id", ""));
        Map<String, List<String>> bySender = new java.util.HashMap<>();
        for (JsonObject message : store.markDeliveredBatch(ids, clientId)) bySender.computeIfAbsent(text(message, "senderClientId", ""), ignored -> new ArrayList<>()).add(text(message, "id", ""));
        for (Map.Entry<String, List<String>> entry : bySender.entrySet()) {
            for (SocketIOClient from : socketsForClient(entry.getKey())) send(from, "message-receipt", receipt(entry.getValue(), "delivered", clientId));
        }
    }

    private void chat(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        if (!withinRate(client, "chat", 40)) { send(client, "notice", "أرسلت رسائل بسرعة كبيرة؛ انتظر قليلاً ثم حاول مجدداً."); return; }
        String text = text(raw, "text", "").strip(); if (text.isEmpty()) return;
        text = text.substring(0, Math.min(MAX_MESSAGE_CHARS, text.length()));
        String channel = "general".equals(text(raw, "channel", "general")) ? "general" : "direct";
        String groupId = text(raw, "groupId", ""); String toId = text(raw, "toId", "");
        if (!groupId.isBlank()) {
            if (!store.isGroupMember(groupId, text(user, "clientId", ""))) { send(client, "notice", "لا تملك صلاحية إرسال رسالة إلى هذه المجموعة."); return; }
        } else if ("direct".equals(channel) && (toId.isBlank() || toId.equals(text(user, "clientId", "")) || !store.hasContact(toId))) {
            { send(client, "notice", "اختر مستخدماً آخر معروفاً للمضيف للمحادثة الخاصة."); return; }
        }
        JsonObject message = new JsonObject(); message.addProperty("id", UUID.randomUUID().toString()); message.addProperty("type", "text"); message.addProperty("text", text);
        message.addProperty("user", text(user, "name", "مستخدم")); message.addProperty("senderId", client.getSessionId().toString()); message.addProperty("senderClientId", text(user, "clientId", "")); message.addProperty("channel", channel);
        if (!toId.isBlank() && groupId.isBlank()) message.addProperty("toId", toId);
        if (!groupId.isBlank()) message.addProperty("groupId", groupId);
        if ("general".equals(channel)) { JsonArray recipients = new JsonArray(); store.contacts().forEach(c -> recipients.add(c.getAsJsonObject().get("clientId").getAsString())); message.add("recipientIds", recipients); }
        message.addProperty("time", System.currentTimeMillis()); message.add("deliveredTo", new JsonArray()); message.add("readBy", new JsonArray());
        try { store.addMessage(message); publishMessage(message); updateDeliveries(message); }
        catch (IOException ex) { send(client, "notice", "تعذر حفظ الرسالة: " + ex.getMessage()); }
    }

    private void broadcast(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        if (!withinRate(client, "chat", 40)) { send(client, "notice", "أرسلت رسائل بسرعة كبيرة؛ انتظر قليلاً ثم حاول مجدداً."); return; }
        String text = text(raw, "text", "").strip(); if (text.isEmpty()) return;
        JsonObject message = new JsonObject(); message.addProperty("id", UUID.randomUUID().toString()); message.addProperty("type", "text"); message.addProperty("text", text.substring(0, Math.min(MAX_MESSAGE_CHARS, text.length())));
        message.addProperty("user", text(user, "name", "مستخدم")); message.addProperty("senderId", client.getSessionId().toString()); message.addProperty("senderClientId", text(user, "clientId", "")); message.addProperty("channel", "broadcast");
        JsonArray recipients = new JsonArray(); store.contacts().forEach(c -> { String id = c.getAsJsonObject().get("clientId").getAsString(); if (!id.equals(text(user, "clientId", ""))) recipients.add(id); });
        message.add("recipientIds", recipients); message.addProperty("time", System.currentTimeMillis()); message.add("deliveredTo", new JsonArray()); message.add("readBy", new JsonArray());
        try { store.addMessage(message); publishMessage(message); send(client, "chat-message", message); updateDeliveries(message); }
        catch (IOException ex) { send(client, "notice", "تعذر حفظ رسالة البث: " + ex.getMessage()); }
    }

    private void createGroup(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        if (!withinRate(client, "group", 10)) { send(client, "notice", "أنشأت مجموعات بسرعة كبيرة؛ حاول لاحقاً."); return; }
        try {
            List<String> memberIds = new ArrayList<>(); JsonElement items = raw.get("members");
            if (items != null && items.isJsonArray()) for (JsonElement item : items.getAsJsonArray()) if (item.isJsonPrimitive()) memberIds.add(item.getAsString());
            JsonObject group = store.createGroup(text(raw, "name", ""), memberIds, text(user, "clientId", ""));
            for (String member : store.groupMembers(group.get("id").getAsString())) { SocketIOClient target = socketForClient(member); if (target != null) send(target, "groups", store.visibleGroups(member)); }
            send(client, "notice", "تم إنشاء مجموعة «" + text(group, "name", "") + "».");
        } catch (Exception ex) { send(client, "notice", ex.getMessage()); }
    }

    private void updateProfile(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        String name = cleanName(text(raw, "name", "مستخدم")); user.addProperty("name", name);
        try { store.rememberContact(text(user, "clientId", ""), name); }
        catch (IOException ex) { send(client, "notice", "تعذر حفظ الاسم: " + ex.getMessage()); }
        broadcastContacts(); broadcastMembers();
    }

    private void markRead(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        try {
            List<String> ids = new ArrayList<>();
            if (raw.has("ids") && raw.get("ids").isJsonArray()) {
                for (JsonElement value : raw.getAsJsonArray("ids")) if (value.isJsonPrimitive() && value.getAsString().matches("(?i)[0-9a-f-]{36}")) ids.add(value.getAsString());
            } else if (text(raw, "id", "").matches("(?i)[0-9a-f-]{36}")) ids.add(text(raw, "id", ""));
            if (ids.isEmpty() || !withinRate(client, "mark-read", 120)) return;
            Map<String, List<String>> bySender = new java.util.HashMap<>();
            for (JsonObject message : store.markReadBatch(ids, text(user, "clientId", ""))) {
                String senderId = text(message, "senderClientId", "");
                bySender.computeIfAbsent(senderId, ignored -> new ArrayList<>()).add(text(message, "id", ""));
            }
            for (Map.Entry<String, List<String>> entry : bySender.entrySet()) {
                for (SocketIOClient from : socketsForClient(entry.getKey())) send(from, "message-receipt", receipt(entry.getValue(), "read", text(user, "clientId", "")));
            }
        } catch (IOException ex) { send(client, "notice", "تعذر حفظ إيصال القراءة."); }
    }

    private void uploadInit(SocketIOClient client, JsonObject raw, com.corundumstudio.socketio.AckRequest ack) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) { acknowledge(ack, false, "لم تنضم إلى الغرفة."); return; }
        try {
            String groupId = text(raw, "groupId", ""), channel = "general".equals(text(raw, "channel", "")) ? "general" : "direct", toId = text(raw, "toId", "");
            if (!groupId.isBlank() && !store.isGroupMember(groupId, text(user, "clientId", ""))) throw new IllegalArgumentException("لا تملك صلاحية المشاركة في هذه المجموعة.");
            if (groupId.isBlank() && "direct".equals(channel) && !store.hasContact(toId)) throw new IllegalArgumentException("المستلم غير معروف للمضيف.");
            raw.addProperty("channel", channel); raw.addProperty("user", text(user, "name", "مستخدم")); raw.addProperty("senderId", client.getSessionId().toString()); raw.addProperty("senderClientId", text(user, "clientId", ""));
            long offset = store.beginUpload(raw, client.getSessionId().toString(), text(user, "clientId", "")); acknowledge(ack, true, "", "offset", offset);
        } catch (Exception ex) { acknowledge(ack, false, ex.getMessage()); }
    }

    private void uploadChunk(SocketIOClient client, JsonObject raw, com.corundumstudio.socketio.AckRequest ack) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null || !withinRate(client, "upload-chunk", 12000)) { acknowledge(ack, false, "الرفع سريع جداً أو لم تنضم إلى الغرفة."); return; }
        try { long offset = store.appendChunk(text(raw, "transferId", ""), raw.get("offset").getAsLong(), text(raw, "data", ""), text(user, "clientId", "")); acknowledge(ack, true, "", "offset", offset); }
        catch (Exception ex) { acknowledge(ack, false, ex.getMessage()); }
    }

    private void uploadComplete(SocketIOClient client, JsonObject raw, com.corundumstudio.socketio.AckRequest ack) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) { acknowledge(ack, false, "لم تنضم إلى الغرفة."); return; }
        try {
            JsonObject message = store.completeUpload(text(raw, "transferId", ""), text(user, "clientId", ""));
            List<String> recipients = recipients(message);
            if ("general".equals(text(message, "channel", ""))) { JsonArray all = new JsonArray(); store.contacts().forEach(c -> all.add(c.getAsJsonObject().get("clientId").getAsString())); message.add("recipientIds", all); }
            if ("general".equals(text(message, "channel", ""))) for (SocketIOClient recipient : onlineClients()) send(recipient, "file-message", message);
            else if (message.has("groupId")) for (String member : store.groupMembers(text(message, "groupId", ""))) { SocketIOClient target = socketForClient(member); if (target != null) send(target, "file-message", message); }
            else { SocketIOClient target = socketForClient(text(message, "toId", "")); if (target != null) send(target, "file-message", message); send(client, "file-message", message); }
            acknowledge(ack, true, "", "fileId", text(message, "id", ""));
        } catch (Exception ex) { acknowledge(ack, false, ex.getMessage()); }
    }

    private void uploadCancel(SocketIOClient client, JsonObject raw) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null) return;
        try { store.cancelUpload(text(raw, "transferId", ""), text(user, "clientId", "")); } catch (IOException ignored) { }
    }

    private synchronized void signal(SocketIOClient client, JsonObject packet) {
        JsonObject user = peers.get(client.getSessionId());
        if (user == null || packet == null || !withinRate(client, "signal", 180)) return;
        JsonObject description = packet.has("description") && packet.get("description").isJsonObject() ? packet.getAsJsonObject("description") : null;
        if (description != null && "call-start".equals(text(description, "type", ""))) {
            String callId = text(description, "callId", "");
            if (callId.isBlank()) callId = UUID.randomUUID().toString();
            String mode = "video".equals(text(description, "mode", "")) ? "video" : "audio";
            if (isClientInActiveCall(text(user, "clientId", ""), null)) {
                send(client, "notice", "أنت مشغول في مكالمة أخرى بالفعل.");
                sendCallEnd(client, callId);
                return;
            }
            String targetClientId = text(packet, "targetClientId", "");
            if (!targetClientId.isBlank()) {
                SocketIOClient target = callSocketForClient(targetClientId);
                if (target == null || target.getSessionId().equals(client.getSessionId())) { send(client, "notice", "المستخدم المحدد غير متصل حالياً."); sendCallEnd(client, callId); return; }
                if (isClientInActiveCall(targetClientId, null)) { sendCallBusy(client, target, callId); sendCallEnd(client, callId); return; }
                JsonObject call = newCall(client.getSessionId().toString(), mode, callId);
                call.getAsJsonArray("invited").add(target.getSessionId().toString());
                activeCalls.put(callId, call);
                sendCallInvitation(client, target, call);
                return;
            }
            List<SocketIOClient> candidates = new ArrayList<>();
            Set<String> requested = new java.util.LinkedHashSet<>();
            if (packet.has("targetClientIds") && packet.get("targetClientIds").isJsonArray()) {
                for (JsonElement element : packet.getAsJsonArray("targetClientIds")) if (element.isJsonPrimitive()) requested.add(element.getAsString());
            } else {
                for (JsonObject peer : peers.values()) {
                    String targetId = text(peer, "clientId", "");
                    if (!targetId.isBlank() && !targetId.equals(text(user, "clientId", ""))) requested.add(targetId);
                }
            }
            for (String targetId : requested) {
                SocketIOClient target = callSocketForClient(targetId);
                if (target == null || target.getSessionId().equals(client.getSessionId())) continue;
                if (isClientInActiveCall(targetId, null)) sendCallBusy(client, target, callId);
                else candidates.add(target);
            }
            if (candidates.isEmpty()) { send(client, "notice", "لا يوجد مستخدم متاح للاتصال حالياً."); sendCallEnd(client, callId); return; }
            JsonObject call = newCall(client.getSessionId().toString(), mode, callId);
            for (SocketIOClient target : candidates) call.getAsJsonArray("invited").add(target.getSessionId().toString());
            activeCalls.put(callId, call);
            for (SocketIOClient target : candidates) sendCallInvitation(client, target, call);
        } else if (description != null && "call-accept".equals(text(description, "type", ""))) {
            JsonObject call = activeCalls.get(text(description, "callId", ""));
            if (call == null) { send(client, "notice", "انتهت الدعوة قبل قبولها."); return; }
            JsonArray invited = call.getAsJsonArray("invited"); String id = client.getSessionId().toString(); boolean wasInvited = false;
            if (invited != null) for (int i = invited.size() - 1; i >= 0; i--) if (id.equals(invited.get(i).getAsString())) { invited.remove(i); wasInvited = true; }
            if (!wasInvited) { send(client, "notice", "لا توجد دعوة نشطة لهذا الجهاز."); return; }
            JsonArray participants = call.getAsJsonArray("participants");
            if (!contains(participants, id)) participants.add(id);
            broadcastCallRoster(call);
        } else if (description != null && ("call-busy".equals(text(description, "type", "")) || "call-decline".equals(text(description, "type", "")))) {
            String id = client.getSessionId().toString();
            JsonObject call = activeCalls.get(text(description, "callId", ""));
            if (call != null) {
                removeValue(call.getAsJsonArray("invited"), id);
                if (call.getAsJsonArray("invited").isEmpty() && call.getAsJsonArray("participants").size() <= 1) endCall(call, id);
            }
            forwardSignal(client, packet, description);
        } else if (description != null && "call-end-all".equals(text(description, "type", ""))) {
            JsonObject call = activeCalls.get(text(description, "callId", ""));
            if (call != null && text(call, "owner", "").equals(client.getSessionId().toString())) endCall(call, client.getSessionId().toString());
        } else if (description != null && "call-end".equals(text(description, "type", ""))) {
            String to = text(packet, "to", "");
            if (!to.isBlank()) {
                JsonObject call = activeCalls.get(text(description, "callId", ""));
                if (call == null) call = findCallForSession(to);
                if (call != null && text(call, "owner", "").equals(to)) removeValue(call.getAsJsonArray("invited"), client.getSessionId().toString());
                forwardSignal(client, packet, description);
                if (call != null && call.getAsJsonArray("invited").isEmpty() && call.getAsJsonArray("participants").size() <= 1) endCall(call, client.getSessionId().toString());
                return;
            }
            removeCallParticipant(client.getSessionId().toString());
        } else {
            forwardSignal(client, packet, description);
        }
    }

    private JsonObject newCall(String owner, String mode, String callId) {
        JsonObject call = new JsonObject(); call.addProperty("owner", owner); call.addProperty("mode", mode); call.addProperty("callId", callId);
        JsonArray participants = new JsonArray(); participants.add(owner); call.add("participants", participants); call.add("invited", new JsonArray()); return call;
    }

    private boolean isClientInActiveCall(String clientId, String exceptCallId) {
        if (clientId == null || clientId.isBlank()) return false;
        for (Map.Entry<String, JsonObject> entry : activeCalls.entrySet()) {
            if (entry.getKey().equals(exceptCallId)) continue;
            JsonObject call = entry.getValue();
            for (String key : List.of("participants", "invited")) {
                JsonArray list = call.getAsJsonArray(key); if (list == null) continue;
                for (JsonElement value : list) {
                    try { JsonObject peer = peers.get(UUID.fromString(value.getAsString())); if (peer != null && clientId.equals(text(peer, "clientId", ""))) return true; }
                    catch (IllegalArgumentException ignored) { }
                }
            }
        }
        return false;
    }

    private JsonObject findCallForSession(String sessionId) {
        for (JsonObject call : activeCalls.values()) {
            if (sessionId.equals(text(call, "owner", "")) || contains(call.getAsJsonArray("participants"), sessionId) || contains(call.getAsJsonArray("invited"), sessionId)) return call;
        }
        return null;
    }

    private boolean contains(JsonArray values, String id) {
        if (values == null) return false;
        for (JsonElement value : values) if (id.equals(value.getAsString())) return true;
        return false;
    }

    private void removeValue(JsonArray values, String id) {
        if (values == null) return;
        for (int i = values.size() - 1; i >= 0; i--) if (id.equals(values.get(i).getAsString())) values.remove(i);
    }

    private void sendCallInvitation(SocketIOClient from, SocketIOClient to, JsonObject call) {
        JsonObject invitation = new JsonObject(); invitation.addProperty("from", from.getSessionId().toString());
        invitation.add("description", obj("type", "call-start", "mode", text(call, "mode", "audio"), "callId", text(call, "callId", ""), "senderClientId", text(peers.get(from.getSessionId()), "clientId", "")));
        send(to, "signal", invitation);
    }

    private void sendCallBusy(SocketIOClient caller, SocketIOClient target, String callId) {
        JsonObject busy = new JsonObject(); busy.addProperty("from", target.getSessionId().toString()); busy.add("description", obj("type", "call-busy", "callId", callId)); send(caller, "signal", busy);
    }

    private void sendCallEnd(SocketIOClient client, String callId) {
        JsonObject end = new JsonObject(); end.addProperty("from", client.getSessionId().toString()); end.add("description", obj("type", "call-end-all", "callId", callId)); send(client, "signal", end);
    }

    private void forwardSignal(SocketIOClient client, JsonObject packet, JsonObject description) {
        String to = text(packet, "to", "");
        try { SocketIOClient target = server.getClient(UUID.fromString(to)); if (target != null) { JsonObject forwarded = new JsonObject(); forwarded.addProperty("from", client.getSessionId().toString()); if (description != null) forwarded.add("description", description); if (packet.has("callId")) forwarded.add("callId", packet.get("callId")); if (packet.has("candidate")) forwarded.add("candidate", packet.get("candidate")); send(target, "signal", forwarded); } } catch (IllegalArgumentException ignored) { }
    }

    private void broadcastCallRoster(JsonObject call) {
        JsonObject description = obj("type", "call-roster", "mode", text(call, "mode", "audio"), "callId", text(call, "callId", ""));
        description.add("participants", call.getAsJsonArray("participants").deepCopy());
        JsonObject packet = new JsonObject(); packet.addProperty("from", text(call, "owner", "")); packet.add("description", description);
        for (JsonElement item : call.getAsJsonArray("participants")) {
            try { SocketIOClient target = server.getClient(UUID.fromString(item.getAsString())); if (target != null) send(target, "signal", packet); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private void removeCallParticipant(String sessionId) {
        JsonObject call = findCallForSession(sessionId); if (call == null) return;
        removeValue(call.getAsJsonArray("invited"), sessionId);
        JsonArray participants = call.getAsJsonArray("participants"); boolean wasParticipant = contains(participants, sessionId);
        removeValue(participants, sessionId);
        if (!wasParticipant) { if (call.getAsJsonArray("invited").isEmpty() && participants.size() <= 1) endCall(call, sessionId); return; }
        JsonObject left = new JsonObject(); left.addProperty("from", sessionId); left.add("description", obj("type", "call-end", "callId", text(call, "callId", "")));
        for (JsonElement item : participants) {
            try { SocketIOClient target = server.getClient(UUID.fromString(item.getAsString())); if (target != null) send(target, "signal", left); }
            catch (IllegalArgumentException ignored) { }
        }
        if (sessionId.equals(text(call, "owner", "")) || (participants.size() <= 1 && call.getAsJsonArray("invited").isEmpty())) endCall(call, sessionId);
        else broadcastCallRoster(call);
    }

    private void endCall(JsonObject call, String from) {
        if (call == null) return;
        activeCalls.remove(text(call, "callId", ""), call);
        JsonObject end = new JsonObject(); end.addProperty("from", from); end.add("description", obj("type", "call-end-all", "callId", text(call, "callId", "")));
        Set<String> recipients = new java.util.LinkedHashSet<>();
        for (String key : List.of("participants", "invited")) {
            JsonArray values = call.getAsJsonArray(key);
            if (values != null) for (JsonElement value : values) if (value.isJsonPrimitive()) recipients.add(value.getAsString());
        }
        for (String id : recipients) {
            try { SocketIOClient target = server.getClient(UUID.fromString(id)); if (target != null) send(target, "signal", end); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void onDisconnect(SocketIOClient client) {
        JsonObject user = peers.remove(client.getSessionId()); rates.keySet().removeIf(key -> key.startsWith(client.getSessionId() + ":"));
        if (user == null) return;
        boolean lastWindow = clientsForClient(text(user, "clientId", "")).isEmpty();
        JsonObject call = findCallForSession(client.getSessionId().toString());
        if (call != null) removeCallParticipant(client.getSessionId().toString());
        if (lastWindow) {
            broadcastContacts(); broadcastMembers();
            JsonObject system = new JsonObject(); system.addProperty("text", text(user, "name", "مستخدم") + " غادر الغرفة"); system.addProperty("time", System.currentTimeMillis()); broadcast("system-message", system);
        }
    }

    private void printListRequest(SocketIOClient client, JsonObject raw) {
        JsonObject requester=peers.get(client.getSessionId()); if(requester==null)return;
        if(!withinRate(client,"print-list",10)){send(client,"print-printers",emptyPrintList(text(raw,"requestId","")));return;}
        if(port==LanDiscovery.PEER_PORT && sharedPrinters.get().isEmpty()){send(client,"print-printers",emptyPrintList(text(raw,"requestId","")));return;}
        SocketIOClient target=socketForClient(text(raw,"targetClientId",""));
        if(target==null){send(client,"print-printers",emptyPrintList(text(raw,"requestId","")));return;}
        JsonObject query=new JsonObject(); query.addProperty("requesterClientId",text(requester,"clientId","")); query.addProperty("requesterSessionId",client.getSessionId().toString()); query.addProperty("requesterName",text(requester,"name","مستخدم")); query.addProperty("requestId",text(raw,"requestId",""));
        send(target,"print-list-request",query);
    }

    private void printListResponse(SocketIOClient client,JsonObject raw){
        JsonObject owner=peers.get(client.getSessionId()); if(owner==null || !withinRate(client,"print-list-response",10))return;
        String requesterId=text(raw,"requesterClientId",""); SocketIOClient requester=socketForSession(text(raw,"requesterSessionId",""));
        JsonObject requesterUser=requester==null?null:peers.get(requester.getSessionId());
        if(requesterUser==null || !requesterId.equals(text(requesterUser,"clientId","")))return;
        JsonObject response=new JsonObject(); response.addProperty("ownerClientId",text(owner,"clientId","")); response.addProperty("ownerName",text(owner,"name","مستخدم"));
        Set<String> installed=new HashSet<>(); if(port==LanDiscovery.PEER_PORT) for(JsonElement printer:sharedPrinters.get()) installed.add(text(printer.getAsJsonObject(),"id",""));
        JsonArray shared=new JsonArray(); JsonElement requested=raw.get("printers");
        if(requested!=null && requested.isJsonArray()) for(JsonElement printer:requested.getAsJsonArray()) if(printer.isJsonObject()){
            if(shared.size()>=16)break;
            JsonObject value=printer.getAsJsonObject(); String id=text(value,"id","");
            if(!id.matches("p-[a-f0-9]{24}") || (port==LanDiscovery.PEER_PORT && !installed.contains(id)) || shared.size()>=16)continue;
            JsonObject safe=new JsonObject();safe.addProperty("id",id);safe.addProperty("name",cleanName(text(value,"name","طابعة")).substring(0,Math.min(80,cleanName(text(value,"name","طابعة")).length())));safe.addProperty("default",value.has("default")&&value.get("default").getAsBoolean());
            JsonArray formats=new JsonArray();if(value.has("formats")&&value.get("formats").isJsonArray())for(JsonElement format:value.getAsJsonArray("formats"))if(formats.size()<8)formats.add(format.getAsString());safe.add("formats",formats);shared.add(safe);
        }
        response.addProperty("requestId",text(raw,"requestId","")); response.add("printers",shared); send(requester,"print-printers",response);
    }

    private static JsonObject emptyPrintList(String requestId){JsonObject result=new JsonObject();result.addProperty("requestId",requestId);result.add("printers",new JsonArray());return result;}

    private void printOffer(SocketIOClient client, JsonObject raw) {
        JsonObject sender=peers.get(client.getSessionId()); if(sender==null || !withinRate(client,"print-offer",10))return;
        String senderId=text(sender,"clientId",""), fileId=text(raw,"fileId",""), targetId=text(raw,"targetClientId",""), printerId=text(raw,"printerId","");
        if(!fileId.matches("(?i)[0-9a-f-]{36}") || !printerId.matches("p-[a-f0-9]{24}") || !targetId.matches("[A-Za-z0-9_-]{16,80}"))return;
        if(port==LanDiscovery.PEER_PORT && sharedPrinters.get().asList().stream().noneMatch(item->printerId.equals(text(item.getAsJsonObject(),"id","")))){send(client,"notice","الطابعة المحددة لا تسمح بمشاركة واصل حالياً.");return;}
        JsonObject file=store.message(fileId);
        if(file==null || !"file".equals(text(file,"type","")) || !senderId.equals(text(file,"senderClientId","")) || store.filePath(fileId)==null){send(client,"notice","لا يمكن طباعة هذا الملف؛ يجب أن تكون أنت من شاركه في هذه المحادثة.");return;}
        if(!file.has("size") || file.get("size").getAsLong()>32L*1024*1024){send(client,"notice","حجم ملف الطباعة يتجاوز الحد 32 ميغابايت.");return;}
        if(!withinFileRecipient(file,senderId,targetId)){send(client,"notice","هذا الملف غير مشارك مع المستخدم المختار.");return;}
        SocketIOClient target=socketForClient(targetId); if(target==null){send(client,"notice","المستخدم المحدد غير متصل.");return;}
        int filePort=LanDiscovery.FILE_PORT;
        String fileHost=port==LanDiscovery.PEER_PORT?"127.0.0.1":localAddressFor(target.getRemoteAddress());
        if(fileHost.isBlank()){send(client,"notice","تعذر تحديد بطاقة الشبكة المناسبة لإرسال ملف الطباعة.");return;}
        JsonObject offer=new JsonObject(); offer.addProperty("requestId",UUID.randomUUID().toString()); offer.addProperty("senderClientId",senderId); offer.addProperty("senderName",text(sender,"name","مستخدم"));
        offer.addProperty("targetClientId",targetId); offer.addProperty("printerId",printerId); offer.addProperty("fileId",fileId); offer.addProperty("fileName",text(file,"name","ملف"));
        offer.addProperty("mime",text(file,"mime","application/octet-stream")); offer.addProperty("size",file.has("size")?file.get("size").getAsLong():0); offer.addProperty("fileHost",fileHost); offer.addProperty("filePort",filePort);
        send(target,"print-offer",offer);
    }

    private static String localAddressFor(java.net.SocketAddress remoteAddress){
        if(!(remoteAddress instanceof java.net.InetSocketAddress socket) || socket.getAddress()==null)return "";
        byte[] remoteBytes=socket.getAddress().getAddress(); if(remoteBytes.length!=4)return "";
        long remote=ipv4(remoteBytes);
        for(SettingsStore.Adapter adapter:SettingsStore.adapters()){
            long local=ipv4(adapter.address),mask=ipv4(adapter.netmask);
            if(local>=0 && mask>=0 && (local&mask)==(remote&mask))return adapter.address;
        }
        return "";
    }
    private static long ipv4(byte[] bytes){long value=0;for(byte part:bytes)value=(value<<8)|(part&255L);return value;}
    private static long ipv4(String text){try{String[] parts=text.split("\\.");if(parts.length!=4)return -1;long value=0;for(String part:parts){int octet=Integer.parseInt(part);if(octet<0||octet>255)return -1;value=(value<<8)|octet;}return value;}catch(RuntimeException ex){return -1;}}

    private boolean withinFileRecipient(JsonObject file,String senderId,String recipientId){
        if(senderId.equals(recipientId))return true;
        String channel=text(file,"channel","general"); if("general".equals(channel)||"broadcast".equals(channel))return true;
        if(text(file,"toId","").equals(recipientId))return true;
        if(file.has("groupId") && store.isGroupMember(text(file,"groupId",""),recipientId))return true;
        if(file.has("recipientIds") && file.get("recipientIds").isJsonArray()) for(JsonElement id:file.getAsJsonArray("recipientIds")) if(recipientId.equals(id.getAsString()))return true;
        return false;
    }

    private void printResult(SocketIOClient client,JsonObject raw){
        JsonObject sender=peers.get(client.getSessionId()); if(sender==null || !withinRate(client,"print-result",20))return;
        SocketIOClient target=socketForClient(text(raw,"targetClientId","")); if(target==null)return;
        JsonObject result=new JsonObject(); result.addProperty("senderClientId",text(sender,"clientId","")); result.addProperty("senderName",text(sender,"name","مستخدم"));
        result.addProperty("requestId",text(raw,"requestId","")); result.addProperty("ok",raw.has("ok")&&raw.get("ok").getAsBoolean()); result.addProperty("status",text(raw,"status","")); send(target,"print-result",result);
    }

    private void publishMessage(JsonObject message) {
        String channel = text(message, "channel", "general");
        if ("general".equals(channel)) {
            for (JsonObject peer : peers.values()) for (SocketIOClient target : socketsForClient(text(peer, "clientId", ""))) send(target, "chat-message", message);
            return;
        }
        for (String id : recipients(message)) for (SocketIOClient target : socketsForClient(id)) send(target, "chat-message", message);
        for (SocketIOClient sender : socketsForClient(text(message, "senderClientId", ""))) send(sender, "chat-message", message);
    }
    private void updateDeliveries(JsonObject message) {
        for (String id : recipients(message)) { List<SocketIOClient> targets = socketsForClient(id); if (!targets.isEmpty()) try { store.markDelivered(text(message, "id", ""), id); for (SocketIOClient target : targets) send(target, "message-receipt", receipt(text(message, "id", ""), "delivered", id)); } catch (IOException ignored) { } }
    }
    private List<String> recipients(JsonObject message) {
        List<String> ids = new ArrayList<>();
        if (message.has("recipientIds") && message.get("recipientIds").isJsonArray()) for (JsonElement item : message.getAsJsonArray("recipientIds")) ids.add(item.getAsString());
        else if (message.has("groupId")) ids.addAll(store.groupMembers(text(message, "groupId", "")));
        else if (message.has("toId")) ids.add(text(message, "toId", ""));
        return ids;
    }
    private void broadcastMembers() {
        for (SocketIOClient recipient : onlineClients()) {
            JsonObject receivingPeer = peers.get(recipient.getSessionId()); if (receivingPeer == null) continue;
            List<JsonObject> members = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (JsonObject peer : peers.values()) {
                String id = text(peer, "clientId", ""); if (!seen.add(id)) continue;
                List<JsonObject> windows = peers.values().stream().filter(item -> id.equals(text(item, "clientId", ""))).toList();
                JsonObject selected = null;
                if (id.equals(text(receivingPeer, "clientId", ""))) {
                    if ("call".equals(text(receivingPeer, "windowRole", ""))) selected = preferredWindow(windows, "call");
                    else { selected = preferredWindow(windows, "monitor"); if (selected == null) selected = preferredWindow(windows, "workspace"); if (selected == null) selected = preferredWindow(windows, "call"); }
                } else {
                    selected = preferredWindow(windows, "monitor"); if (selected == null) selected = preferredWindow(windows, "workspace"); if (selected == null) selected = preferredWindow(windows, "call");
                }
                if (selected != null) members.add(selected);
            }
            send(recipient, "members", members);
        }
    }
    private JsonObject preferredWindow(List<JsonObject> windows, String role) { for (JsonObject window : windows) if (role.equals(text(window, "windowRole", "monitor"))) return window; return null; }
    private List<Object> contactList() {
        List<Object> out = new ArrayList<>();
        for (JsonElement element : store.contacts()) { JsonObject contact=element.getAsJsonObject(); Map<String,Object> item = map(contact); item.put("online", socketForClient(text(contact,"clientId","")) != null); out.add(item); }
        return out;
    }
    private void broadcastContacts() { broadcast("contacts", contactList()); }
    private Collection<SocketIOClient> onlineClients() { return server == null ? List.of() : server.getAllClients(); }
    private List<SocketIOClient> socketsForClient(String clientId) { List<SocketIOClient> out = new ArrayList<>(); SocketIOServer current = server; if (current == null) return out; for (Map.Entry<UUID,JsonObject> item : peers.entrySet()) if (clientId.equals(text(item.getValue(), "clientId", ""))) { SocketIOClient socket = current.getClient(item.getKey()); if (socket != null && socket.isChannelOpen()) out.add(socket); } return out; }
    private List<SocketIOClient> clientsForClient(String clientId) { return socketsForClient(clientId); }
    private SocketIOClient socketForClient(String clientId) { List<SocketIOClient> sockets = socketsForClient(clientId); return sockets.isEmpty() ? null : sockets.get(0); }
    private SocketIOClient socketForSession(String sessionId) { try { SocketIOServer current=server; SocketIOClient client=current==null?null:current.getClient(UUID.fromString(sessionId)); return client!=null && client.isChannelOpen()?client:null; } catch(IllegalArgumentException ignored) { return null; } }
    private SocketIOClient callSocketForClient(String clientId) { for (SocketIOClient socket : socketsForClient(clientId)) { JsonObject peer = peers.get(socket.getSessionId()); if (peer != null && "call".equals(text(peer, "windowRole", ""))) return socket; } return socketForClient(clientId); }
    private void send(SocketIOClient client, String event, Object value) { if (client != null && client.isChannelOpen()) try { client.sendEvent(event, plain(value)); } catch (Exception ex) { System.err.println("Wasel event " + event + ": " + ex.getMessage()); } }
    private void broadcast(String event, Object value) { for (SocketIOClient client : onlineClients()) send(client,event,value); }
    private void broadcastExcept(SocketIOClient except, String event, Object value) { for (SocketIOClient client : onlineClients()) if (!client.getSessionId().equals(except.getSessionId())) send(client,event,value); }
    private boolean withinRate(SocketIOClient client, String event, int limit) {
        String key=client.getSessionId()+":"+event; long now=System.currentTimeMillis(); Deque<Long> list=rates.computeIfAbsent(key,k->new ArrayDeque<>());
        synchronized(list) { while(!list.isEmpty() && now-list.peekFirst()>60000) list.removeFirst(); if(list.size()>=limit) return false; list.addLast(now); return true; }
    }
    private static void acknowledge(com.corundumstudio.socketio.AckRequest ack, boolean ok, String error, Object... extra) {
        if (ack == null || !ack.isAckRequested()) return; Map<String,Object> response=new java.util.LinkedHashMap<>(); response.put("ok",ok); if(!ok) response.put("error",error==null?"تعذر إكمال العملية.":error);
        for(int i=0;i+1<extra.length;i+=2) response.put(String.valueOf(extra[i]),extra[i+1]); ack.sendAckData(response);
    }
    private static JsonObject json(Map<?,?> map) { return map == null ? new JsonObject() : GSON.toJsonTree(map).getAsJsonObject(); }
    private static Object plain(Object value) {
        if (value instanceof JsonElement element) return GSON.fromJson(element,Object.class);
        if (value instanceof Map<?,?> source) { Map<String,Object> out=new java.util.LinkedHashMap<>(); source.forEach((key,item)->out.put(String.valueOf(key),plain(item))); return out; }
        if (value instanceof Iterable<?> source) { List<Object> out=new ArrayList<>(); source.forEach(item->out.add(plain(item))); return out; }
        return value;
    }
    private static Map<String,Object> map(JsonObject value) { return GSON.fromJson(value,Map.class); }
    private static JsonObject receipt(String id,String kind,String clientId) { JsonObject out=new JsonObject(); out.addProperty("id",id); out.addProperty("kind",kind); out.addProperty("clientId",clientId); return out; }
    private static JsonObject receipt(List<String> ids,String kind,String clientId) { JsonObject out=new JsonObject(); JsonArray values=new JsonArray(); ids.forEach(values::add); out.add("ids",values); out.addProperty("kind",kind); out.addProperty("clientId",clientId); return out; }
    private static JsonObject obj(String... pairs) { JsonObject out=new JsonObject(); for(int i=0;i+1<pairs.length;i+=2) out.addProperty(pairs[i],pairs[i+1]); return out; }
    private static String text(JsonObject object,String key,String fallback) { return LanDiscovery.text(object,key,fallback); }
    private static String cleanName(String value) { String name=value==null?"":value.replaceAll("[\\r\\n\\u0000]"," ").strip(); return name.isEmpty()?"مستخدم":name.substring(0,Math.min(40,name.length())); }

    @Override public void close() {
        SocketIOServer current;
        synchronized(this) { if(closing)return; closing=true; current=server; server=null; activeCalls.clear(); peers.clear(); rates.clear(); }
        if(current!=null) try { current.stop(); } catch(Exception ex) { notice.accept("تعذر إيقاف الغرفة بشكل نظيف."); }
    }
}
