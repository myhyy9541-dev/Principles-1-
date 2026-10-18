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

public final class LanRoomServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGE_CHARS = 5000;
    private final RoomStore store;
    private final Consumer<String> notice;
    private final int port;
    private final Map<UUID, JsonObject> peers = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> rates = new ConcurrentHashMap<>();
    private volatile SocketIOServer server;
    private volatile JsonObject activeCall;

    public LanRoomServer(RoomStore store, Consumer<String> notice) { this(store, notice, LanDiscovery.SOCKET_PORT); }
    public LanRoomServer(RoomStore store, Consumer<String> notice, int port) { this.store = store; this.notice = notice; this.port = port; }

    public synchronized void start() {
        if (server != null) return;
        Configuration config = new Configuration(); config.setHostname("0.0.0.0"); config.setPort(port);
        config.setMaxHttpContentLength(2 * 1024 * 1024); config.setMaxFramePayloadLength(2 * 1024 * 1024);
        config.setEnableCors(true); config.setOrigin("*"); config.setPingInterval(25000); config.setPingTimeout(60000);
        SocketIOServer next = new SocketIOServer(config);
        next.addConnectListener(this::onConnect); next.addDisconnectListener(this::onDisconnect);
        next.addEventListener("join", Map.class, (client, data, ack) -> join(client, json(data)));
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
        next.start(); server = next;
    }

    private void onConnect(SocketIOClient client) { }
    private void join(SocketIOClient client, JsonObject raw) {
        if (peers.containsKey(client.getSessionId())) return;
        String clientId = text(raw, "clientId", "");
        if (!clientId.matches("[A-Za-z0-9_-]{16,80}")) { send(client, "notice", "تعذرت قراءة هوية هذا الجهاز؛ أعد تشغيل التطبيق."); return; }
        String name = cleanName(text(raw, "name", "مستخدم"));
        JsonObject user = new JsonObject(); user.addProperty("id", client.getSessionId().toString()); user.addProperty("clientId", clientId); user.addProperty("name", name); user.addProperty("joinedAt", System.currentTimeMillis());
        peers.put(client.getSessionId(), user);
        try { store.rememberContact(clientId, name); }
        catch (IOException ex) { send(client, "notice", "تعذر حفظ جهة الاتصال: " + ex.getMessage()); }
        send(client, "identity", obj("clientId", clientId)); send(client, "history", store.historyFor(clientId));
        send(client, "groups", store.visibleGroups(clientId)); send(client, "contacts", contactList());
        for (JsonObject message : store.offlineFor(clientId)) {
            try {
                store.markDelivered(text(message, "id", ""), clientId); send(client, "chat-message", message);
                SocketIOClient from = socketForClient(text(message, "senderClientId", ""));
                if (from != null) send(from, "message-receipt", receipt(text(message, "id", ""), "delivered", clientId));
            } catch (IOException ex) { send(client, "notice", "تعذر تحديث إيصال الرسالة: " + ex.getMessage()); }
        }
        broadcastContacts(); broadcastMembers();
        JsonObject system = new JsonObject(); system.addProperty("text", name + " انضم إلى الغرفة"); system.addProperty("time", System.currentTimeMillis()); broadcast("system-message", system);
        JsonObject call = activeCall;
        if (call != null) { JsonObject packet = new JsonObject(); packet.addProperty("from", text(call, "owner", "")); packet.add("description", obj("type", "call-start", "mode", text(call, "mode", "audio"))); send(client, "signal", packet); }
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
            JsonObject message = store.markRead(text(raw, "id", ""), text(user, "clientId", "")); if (message == null) return;
            SocketIOClient from = socketForClient(text(message, "senderClientId", "")); if (from != null) send(from, "message-receipt", receipt(text(message, "id", ""), "read", text(user, "clientId", "")));
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
        JsonObject user = peers.get(client.getSessionId()); if (user == null || !withinRate(client, "upload-chunk", 1200)) { acknowledge(ack, false, "الرفع سريع جداً أو لم تنضم إلى الغرفة."); return; }
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

    private void signal(SocketIOClient client, JsonObject packet) {
        JsonObject user = peers.get(client.getSessionId()); if (user == null || packet == null || !withinRate(client, "signal", 180)) return;
        JsonObject description = packet.has("description") && packet.get("description").isJsonObject() ? packet.getAsJsonObject("description") : null;
        if (description != null && "call-start".equals(text(description, "type", ""))) {
            String targetClientId = text(packet, "targetClientId", "");
            if (!targetClientId.isBlank()) {
                SocketIOClient target = socketForClient(targetClientId);
                if (target == null || target.getSessionId().equals(client.getSessionId())) { send(client, "notice", "المستخدم المحدد غير متصل حالياً."); return; }
                JsonObject invitation = new JsonObject(); invitation.addProperty("from", client.getSessionId().toString()); invitation.add("description", obj("type", "call-start", "mode", text(description, "mode", "audio")));
                send(target, "signal", invitation); return;
            }
            JsonObject call = new JsonObject(); call.addProperty("owner", client.getSessionId().toString()); call.addProperty("mode", "video".equals(text(description, "mode", "")) ? "video" : "audio"); activeCall = call;
            JsonObject notice = new JsonObject(); notice.addProperty("from", client.getSessionId().toString()); notice.add("description", obj("type", "call-start", "mode", text(call, "mode", "audio"))); broadcastExcept(client, "signal", notice);
        } else if (description != null && "call-end".equals(text(description, "type", ""))) {
            String to = text(packet, "to", "");
            if (!to.isBlank()) { forwardSignal(client, packet, description); return; }
            activeCall = null; JsonObject notice = new JsonObject(); notice.addProperty("from", client.getSessionId().toString()); notice.add("description", obj("type", "call-end")); broadcastExcept(client, "signal", notice);
        } else {
            forwardSignal(client, packet, description);
        }
    }

    private void forwardSignal(SocketIOClient client, JsonObject packet, JsonObject description) {
        String to = text(packet, "to", "");
        try { SocketIOClient target = server.getClient(UUID.fromString(to)); if (target != null) { JsonObject forwarded = new JsonObject(); forwarded.addProperty("from", client.getSessionId().toString()); if (description != null) forwarded.add("description", description); if (packet.has("candidate")) forwarded.add("candidate", packet.get("candidate")); send(target, "signal", forwarded); } } catch (IllegalArgumentException ignored) { }
    }

    private void onDisconnect(SocketIOClient client) {
        JsonObject user = peers.remove(client.getSessionId()); rates.keySet().removeIf(key -> key.startsWith(client.getSessionId() + ":"));
        if (user == null) return;
        if (activeCall != null && text(activeCall, "owner", "").equals(client.getSessionId().toString())) {
            activeCall = null; JsonObject event = new JsonObject(); event.addProperty("from", client.getSessionId().toString()); event.add("description", obj("type", "call-end")); broadcast("signal", event);
        }
        broadcastContacts(); broadcastMembers();
        JsonObject system = new JsonObject(); system.addProperty("text", text(user, "name", "مستخدم") + " غادر الغرفة"); system.addProperty("time", System.currentTimeMillis()); broadcast("system-message", system);
    }

    private void publishMessage(JsonObject message) {
        String channel = text(message, "channel", "general");
        if ("general".equals(channel)) { broadcast("chat-message", message); return; }
        for (String id : recipients(message)) { SocketIOClient target = socketForClient(id); if (target != null) send(target, "chat-message", message); }
        SocketIOClient sender = socketForClient(text(message, "senderClientId", "")); if (sender != null) send(sender, "chat-message", message);
    }
    private void updateDeliveries(JsonObject message) {
        for (String id : recipients(message)) { SocketIOClient target = socketForClient(id); if (target != null) try { store.markDelivered(text(message, "id", ""), id); send(target, "message-receipt", receipt(text(message, "id", ""), "delivered", id)); } catch (IOException ignored) { } }
    }
    private List<String> recipients(JsonObject message) {
        List<String> ids = new ArrayList<>();
        if (message.has("recipientIds") && message.get("recipientIds").isJsonArray()) for (JsonElement item : message.getAsJsonArray("recipientIds")) ids.add(item.getAsString());
        else if (message.has("groupId")) ids.addAll(store.groupMembers(text(message, "groupId", "")));
        else if (message.has("toId")) ids.add(text(message, "toId", ""));
        return ids;
    }
    private void broadcastMembers() { broadcast("members", new ArrayList<>(peers.values())); }
    private List<Object> contactList() {
        List<Object> out = new ArrayList<>();
        for (JsonElement element : store.contacts()) { JsonObject contact=element.getAsJsonObject(); Map<String,Object> item = map(contact); item.put("online", socketForClient(text(contact,"clientId","")) != null); out.add(item); }
        return out;
    }
    private void broadcastContacts() { broadcast("contacts", contactList()); }
    private Collection<SocketIOClient> onlineClients() { return server == null ? List.of() : server.getAllClients(); }
    private SocketIOClient socketForClient(String clientId) { for (Map.Entry<UUID,JsonObject> item : peers.entrySet()) if (clientId.equals(text(item.getValue(),"clientId",""))) { SocketIOServer current=server; if (current != null) return current.getClient(item.getKey()); } return null; }
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
    private static JsonObject obj(String... pairs) { JsonObject out=new JsonObject(); for(int i=0;i+1<pairs.length;i+=2) out.addProperty(pairs[i],pairs[i+1]); return out; }
    private static String text(JsonObject object,String key,String fallback) { return LanDiscovery.text(object,key,fallback); }
    private static String cleanName(String value) { String name=value==null?"":value.replaceAll("[\\r\\n\\u0000]"," ").strip(); return name.isEmpty()?"مستخدم":name.substring(0,Math.min(40,name.length())); }

    @Override public synchronized void close() { SocketIOServer current=server; server=null; activeCall=null; peers.clear(); if(current!=null) try { current.stop(); } catch(Exception ex) { notice.accept("تعذر إيقاف الغرفة بشكل نظيف."); } }
}
