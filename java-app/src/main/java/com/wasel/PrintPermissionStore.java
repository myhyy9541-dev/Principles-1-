package com.wasel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Local opt-in trust list; each approval is scoped to one sender and one printer. */
public final class PrintPermissionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private final JsonArray grants = new JsonArray();

    public PrintPermissionStore(Path path) {
        this.path = path;
        if (Files.isRegularFile(path)) try {
            var parsed = com.google.gson.JsonParser.parseString(Files.readString(path));
            if (parsed.isJsonArray()) for (JsonElement element : parsed.getAsJsonArray()) if (valid(element)) grants.add(element.getAsJsonObject().deepCopy());
        } catch (Exception ignored) { }
    }

    private static boolean valid(JsonElement element) {
        if (element == null || !element.isJsonObject()) return false;
        JsonObject item = element.getAsJsonObject();
        return item.has("senderClientId") && item.has("printerId") && item.get("senderClientId").getAsString().matches("[A-Za-z0-9_-]{16,80}")
                && item.get("printerId").getAsString().matches("p-[a-f0-9]{24}");
    }

    public synchronized boolean allowed(String senderId, String printerId) {
        return grants.asList().stream().anyMatch(item -> senderId.equals(item.getAsJsonObject().get("senderClientId").getAsString())
                && printerId.equals(item.getAsJsonObject().get("printerId").getAsString()));
    }

    public synchronized JsonArray list() { JsonArray copy = new JsonArray(); grants.forEach(item -> copy.add(item.deepCopy())); return copy; }

    public synchronized void grant(String senderId, String senderName, String printerId, String printerName) throws IOException {
        if (senderId == null || !senderId.matches("[A-Za-z0-9_-]{16,80}") || printerId == null || !printerId.matches("p-[a-f0-9]{24}"))
            throw new IllegalArgumentException("هوية المرسل أو الطابعة غير صالحة.");
        revoke(senderId, printerId);
        JsonObject grant = new JsonObject(); grant.addProperty("senderClientId", senderId); grant.addProperty("senderName", clean(senderName));
        grant.addProperty("printerId", printerId); grant.addProperty("printerName", clean(printerName)); grant.addProperty("grantedAt", System.currentTimeMillis());
        grants.add(grant); persist();
    }

    public synchronized void revoke(String senderId, String printerId) throws IOException {
        for (int i = grants.size() - 1; i >= 0; i--) {
            JsonObject item = grants.get(i).getAsJsonObject();
            if (senderId.equals(item.get("senderClientId").getAsString()) && printerId.equals(item.get("printerId").getAsString())) grants.remove(i);
        }
        persist();
    }

    private static String clean(String text) { return text == null ? "" : text.replaceAll("[\\r\\n\\u0000]", " ").strip().substring(0, Math.min(80, text.replaceAll("[\\r\\n\\u0000]", " ").strip().length())); }
    private void persist() throws IOException {
        Files.createDirectories(path.getParent()); Path temp = path.resolveSibling(path.getFileName() + ".tmp"); Files.writeString(temp, GSON.toJson(grants));
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
