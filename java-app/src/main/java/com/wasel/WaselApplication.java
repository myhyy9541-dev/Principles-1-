package com.wasel;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefJSDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefJSDialogHandler;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.misc.BoolRef;
import com.google.gson.JsonObject;

public final class WaselApplication {
    private WaselRuntime runtime;
    private CefApp cefApp;
    private CefClient cefClient;
    private CefBrowser browser;
    private JFrame frame;
    private final Map<String, ManagedWindow> windows = new HashMap<>();
    private final Map<String, String> keyedWindows = new HashMap<>();
    private TrayIcon trayIcon;
    private volatile boolean exiting;
    private FileChannel lockChannel;
    private FileLock processLock;
    private final ExecutorService downloadPreparation = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "wasel-download-prepare"); thread.setDaemon(true); return thread;
    });

    public static void main(String[] args) { new WaselApplication().start(); }

    private void start() {
        try {
            Path dataRoot = WaselRuntime.resolveDataRoot(); Files.createDirectories(dataRoot);
            lockChannel = FileChannel.open(dataRoot.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            processLock = lockChannel.tryLock();
            if (processLock == null) { showError("واصل يعمل بالفعل على هذا الجهاز."); lockChannel.close(); lockChannel=null; return; }
            runtime = new WaselRuntime();
            runtime.setWindowController(this::handleWindowRequest);
            runtime.setDirectoryChooser(result -> SwingUtilities.invokeLater(() -> {
                try {
                    JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("اختيار مجلد الحفظ");
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                    int choice = chooser.showOpenDialog(frame);
                    result.complete(choice == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null
                            ? chooser.getSelectedFile().toPath().toAbsolutePath().normalize() : null);
                } catch (Exception ex) { result.completeExceptionally(ex); }
            }));
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(runtime.dataRoot().resolve("jcef-runtime").toFile());
            builder.setAppHandler(new MavenCefAppHandlerAdapter() { });
            builder.addJcefArgs("--enable-media-stream", "--use-fake-ui-for-media-stream", "--no-default-browser-check", "--disable-background-networking");
            builder.getCefSettings().windowless_rendering_enabled = false;
            builder.getCefSettings().root_cache_path = runtime.dataRoot().resolve("browser-profile").toString();
            builder.getCefSettings().cache_path = runtime.dataRoot().resolve("browser-profile").resolve("default").toString();
            builder.getCefSettings().persist_session_cookies = true;
            builder.getCefSettings().locale = "ar";
            builder.getCefSettings().log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING;
            cefApp = builder.build();
            cefClient = cefApp.createClient();
            installFileDialog(); installDownloadHandler(); installJsDialogHandler();
            cefClient.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                @Override public boolean onBeforePopup(CefBrowser current, org.cef.browser.CefFrame frame, String targetUrl, String targetFrameName) { return true; }
            });
            SwingUtilities.invokeAndWait(() -> {
                String monitorId = UUID.randomUUID().toString().replace("-", "");
                ManagedWindow main = createBrowserWindow("monitor", monitorId, "", "", true);
                browser = main.browser; frame = main.frame;
                installTrayIcon();
                createBrowserWindow("call", UUID.randomUUID().toString().replace("-", ""), "", "", false);
            });
        } catch (Throwable error) {
            error.printStackTrace();
            if (runtime != null) runtime.close();
            showError("تعذر تشغيل واصل Java: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            shutdown();
        }
    }

    private ManagedWindow createBrowserWindow(String role, String id, String targetId, String targetName, boolean visible) {
        String key = "monitor".equals(role) ? "monitor" : "call".equals(role) ? "call" : "workspace:" + targetId;
        String url = runtime.startUrl() + "?view=" + encode(role) + "&windowId=" + encode(id);
        if (!targetId.isBlank()) url += "&targetId=" + encode(targetId) + "&targetName=" + encode(targetName);
        final String pageUrl = url;
        CefBrowser nextBrowser = cefClient.createBrowser(pageUrl, false, false);
        JFrame nextFrame = new JFrame("monitor".equals(role) ? "واصل - المتابعة" : "call".equals(role) ? "واصل - المكالمة" : "واصل - " + targetName);
        nextFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        nextFrame.add(nextBrowser.getUIComponent());
        Dimension minimum = "monitor".equals(role) ? new Dimension(300, 500) : new Dimension(760, 560);
        Dimension size = "monitor".equals(role) ? new Dimension(340, 690) : "call".equals(role) ? new Dimension(1100, 760) : new Dimension(1100, 760);
        nextFrame.setMinimumSize(minimum); nextFrame.setSize(size); nextFrame.setLocationRelativeTo(null);
        ManagedWindow managed = new ManagedWindow(id, key, role, nextBrowser, nextFrame);
        windows.put(id, managed); keyedWindows.put(key, id);
        if ("monitor".equals(role)) { this.browser = nextBrowser; this.frame = nextFrame; }
        if ("monitor".equals(role)) nextFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { hideMainWindow(); }
        });
        else if ("call".equals(role)) nextFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                nextBrowser.executeJavaScript("window.closeCallFromNative && window.closeCallFromNative()", pageUrl, 0);
                nextFrame.setVisible(false);
            }
        });
        else nextFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { disposeWindow(id); }
        });
        if (visible) { nextFrame.setVisible(true); nextFrame.toFront(); }
        return managed;
    }

    private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }

    private CompletableFuture<String> handleWindowRequest(JsonObject request) {
        CompletableFuture<String> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                String action = request.has("action") ? request.get("action").getAsString() : "";
                if ("open".equals(action)) {
                    String view = request.has("view") ? request.get("view").getAsString() : "";
                    if ("call".equals(view)) {
                        String callId = keyedWindows.get("call"); ManagedWindow callWindow = callId == null ? null : windows.get(callId);
                        if (callWindow == null) callWindow = createBrowserWindow("call", UUID.randomUUID().toString().replace("-", ""), "", "", false);
                        callWindow.frame.setVisible(true); callWindow.frame.toFront(); callWindow.frame.requestFocus(); result.complete(callWindow.id); return;
                    }
                    String targetId = request.get("targetId").getAsString();
                    String targetName = request.has("targetName") ? request.get("targetName").getAsString() : "مستخدم";
                    String key = "workspace:" + targetId;
                    String existingId = keyedWindows.get(key);
                    ManagedWindow existing = existingId == null ? null : windows.get(existingId);
                    if (existing != null) { existing.frame.setVisible(true); existing.frame.toFront(); result.complete(existing.id); return; }
                    result.complete(createBrowserWindow("workspace", UUID.randomUUID().toString().replace("-", ""), targetId, targetName, true).id);
                    return;
                }
                String id = request.has("windowId") ? request.get("windowId").getAsString() : "";
                ManagedWindow window = windows.get(id);
                if (window == null) { result.completeExceptionally(new IllegalArgumentException("النافذة لم تعد متاحة.")); return; }
                switch (action) {
                    case "show" -> { window.frame.setVisible(true); window.frame.toFront(); window.frame.requestFocus(); }
                    case "hide" -> window.frame.setVisible(false);
                    case "close" -> { if ("call".equals(window.role)) window.frame.setVisible(false); else if ("monitor".equals(window.role)) hideMainWindow(); else disposeWindow(id); }
                    default -> throw new IllegalArgumentException("إجراء النافذة غير صالح.");
                }
                result.complete(window.id);
            } catch (Exception error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private void disposeWindow(String id) {
        ManagedWindow window = windows.remove(id); if (window == null) return;
        keyedWindows.remove(window.key, id);
        try { window.browser.close(true); } catch (Exception ignored) { }
        window.frame.dispose();
    }

    private void hideMainWindow() {
        if (frame == null) return;
        if (trayIcon != null) frame.setVisible(false);
        else frame.setState(Frame.ICONIFIED);
    }

    private void installTrayIcon() {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu(); MenuItem restore = new MenuItem("إظهار واصل"); MenuItem exit = new MenuItem("إنهاء واصل");
            restore.addActionListener(event -> SwingUtilities.invokeLater(() -> { frame.setVisible(true); frame.setState(Frame.NORMAL); frame.toFront(); }));
            exit.addActionListener(event -> shutdown()); menu.add(restore); menu.addSeparator(); menu.add(exit);
            Image icon = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB); Graphics2D graphics = ((BufferedImage)icon).createGraphics();
            graphics.setColor(new java.awt.Color(32,173,132)); graphics.fillRoundRect(1,1,30,30,9,9); graphics.setColor(java.awt.Color.WHITE); graphics.setFont(new java.awt.Font("Dialog",java.awt.Font.BOLD,21)); graphics.drawString("و",7,24); graphics.dispose();
            trayIcon = new TrayIcon(icon,"واصل — تواصل المنشأة",menu); trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> SwingUtilities.invokeLater(() -> { frame.setVisible(true); frame.setState(Frame.NORMAL); frame.toFront(); }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ex) { trayIcon = null; System.err.println("Wasel tray icon: " + ex.getMessage()); }
    }

    private static final class ManagedWindow {
        final String id, key, role; final CefBrowser browser; final JFrame frame;
        ManagedWindow(String id,String key,String role,CefBrowser browser,JFrame frame) { this.id=id; this.key=key; this.role=role; this.browser=browser; this.frame=frame; }
    }

    private void installFileDialog() {
        cefClient.addDialogHandler(new CefDialogHandler() {
            @Override public boolean onFileDialog(CefBrowser source, FileDialogMode mode, String title, String defaultFilePath,
                                                  Vector<String> acceptFilters, Vector<String> acceptExtensions,
                                                  Vector<String> acceptMimeTypes, CefFileDialogCallback callback) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle(title == null || title.isBlank() ? "اختيار ملف" : title);
                        chooser.setMultiSelectionEnabled(mode == FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE);
                        if (mode == FileDialogMode.FILE_DIALOG_OPEN_FOLDER) chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                        if (mode == FileDialogMode.FILE_DIALOG_SAVE) { chooser.setDialogType(JFileChooser.SAVE_DIALOG); chooser.setSelectedFile(new java.io.File(defaultFilePath == null ? "" : defaultFilePath)); }
                        else chooser.setDialogType(JFileChooser.OPEN_DIALOG);
                        if (acceptExtensions != null && !acceptExtensions.isEmpty()) {
                            Vector<String> extensions = new Vector<>();
                            for (String ext : acceptExtensions) { String clean = ext.replace("*.", "").replace(".", "").trim(); if (clean.matches("[A-Za-z0-9_-]{1,20}")) extensions.add(clean); }
                            if (!extensions.isEmpty()) chooser.setFileFilter(new FileNameExtensionFilter(String.join(", ", extensions), extensions.toArray(String[]::new)));
                        }
                        int result = mode == FileDialogMode.FILE_DIALOG_SAVE ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
                        if (result == JFileChooser.APPROVE_OPTION) {
                            Vector<String> paths = new Vector<>();
                            if (mode == FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE) for (java.io.File file : chooser.getSelectedFiles()) paths.add(file.getAbsolutePath());
                            else if (chooser.getSelectedFile() != null) paths.add(chooser.getSelectedFile().getAbsolutePath());
                            callback.Continue(paths);
                        } else callback.Continue(new Vector<>());
                    } catch (Exception ex) { callback.Continue(new Vector<>()); }
                });
                return true;
            }
        });
    }

    private void installDownloadHandler() {
        cefClient.addDownloadHandler(new CefDownloadHandler() {
            @Override public boolean onBeforeDownload(CefBrowser source, CefDownloadItem item, String suggestedName, CefBeforeDownloadCallback callback) {
                Path defaultDirectory = runtime.defaultSaveDirectory();
                if (defaultDirectory != null) {
                    downloadPreparation.execute(() -> {
                        String destination;
                        try {
                            Files.createDirectories(defaultDirectory);
                            destination = uniqueDestination(defaultDirectory, safeDownloadName(suggestedName)).toString();
                        } catch (Exception ex) {
                            SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(frame, "تعذر حفظ الملف في المجلد المحدد. اختر موضعاً آخر للحفظ.", "واصل", JOptionPane.WARNING_MESSAGE);
                                callback.Continue("", true);
                            });
                            return;
                        }
                        callback.Continue(destination, false);
                    });
                    return true;
                }
                SwingUtilities.invokeLater(() -> {
                    try {
                        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("حفظ الملف المشترك"); chooser.setDialogType(JFileChooser.SAVE_DIALOG);
                        chooser.setSelectedFile(new java.io.File(suggestedName == null || suggestedName.isBlank() ? "shared-file" : suggestedName));
                        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) callback.Continue(chooser.getSelectedFile().getAbsolutePath(), false);
                        else callback.Continue("", false);
                    } catch (Exception ignored) { callback.Continue("", false); }
                });
                return true;
            }
            @Override public void onDownloadUpdated(CefBrowser source, CefDownloadItem item, CefDownloadItemCallback callback) { }
        });
    }

    private static String safeDownloadName(String suggestedName) {
        String name = suggestedName == null ? "" : suggestedName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").strip();
        return name.isBlank() || name.equals(".") || name.equals("..") ? "shared-file" : name;
    }

    private static Path uniqueDestination(Path directory, String filename) throws IOException {
        Path candidate = directory.resolve(filename);
        if (!Files.exists(candidate)) return candidate;
        String extension = ""; String base = filename;
        int dot = filename.lastIndexOf('.');
        if (dot > 0) { base = filename.substring(0, dot); extension = filename.substring(dot); }
        for (int index = 2; index < Integer.MAX_VALUE; index++) {
            candidate = directory.resolve(base + " (" + index + ")" + extension);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("No available destination filename");
    }

    private void installJsDialogHandler() {
        cefClient.addJSDialogHandler(new CefJSDialogHandler() {
            @Override public boolean onJSDialog(CefBrowser source, String origin, JSDialogType type, String message, String defaultPrompt, CefJSDialogCallback callback, BoolRef suppressMessage) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (type == JSDialogType.JSDIALOGTYPE_ALERT) { JOptionPane.showMessageDialog(frame, message, "واصل", JOptionPane.INFORMATION_MESSAGE); callback.Continue(true, ""); }
                        else if (type == JSDialogType.JSDIALOGTYPE_CONFIRM) callback.Continue(JOptionPane.showConfirmDialog(frame, message, "واصل", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION, "");
                        else { Object result = JOptionPane.showInputDialog(frame, message, "واصل", JOptionPane.QUESTION_MESSAGE, null, null, defaultPrompt == null ? "" : defaultPrompt); callback.Continue(result != null, result == null ? "" : result.toString()); }
                    } catch (Exception ignored) { callback.Continue(false, ""); }
                });
                return true;
            }
            @Override public boolean onBeforeUnloadDialog(CefBrowser source, String message, boolean reload, CefJSDialogCallback callback) { callback.Continue(true, ""); return true; }
            @Override public void onResetDialogState(CefBrowser source) { }
            @Override public void onDialogClosed(CefBrowser source) { }
        });
    }

    private static void showError(String message) {
        try { if (java.awt.GraphicsEnvironment.isHeadless()) System.err.println(message); else SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, message, "واصل", JOptionPane.ERROR_MESSAGE)); }
        catch (Exception ignored) { System.err.println(message); }
    }

    private synchronized void shutdown() {
        if (exiting) return;
        exiting = true;
        downloadPreparation.shutdownNow();
        if (runtime != null) runtime.close();
        for (ManagedWindow window : windows.values()) {
            try { window.browser.close(true); } catch (Exception ignored) { }
            try { window.frame.dispose(); } catch (Exception ignored) { }
        }
        windows.clear(); keyedWindows.clear();
        if (cefClient != null) try { cefClient.dispose(); } catch (Exception ignored) { }
        if (cefApp != null) try { cefApp.dispose(); } catch (Exception ignored) { }
        if (trayIcon != null) try { SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) { }
        try { if (processLock != null && processLock.isValid()) processLock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
        browser = null; frame = null;
        System.exit(0);
    }
}
