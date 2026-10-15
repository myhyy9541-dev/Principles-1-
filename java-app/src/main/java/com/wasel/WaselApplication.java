package com.wasel;

import java.awt.Dimension;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;
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

public final class WaselApplication {
    private WaselRuntime runtime;
    private CefApp cefApp;
    private CefClient cefClient;
    private CefBrowser browser;
    private JFrame frame;
    private FileChannel lockChannel;
    private FileLock processLock;

    public static void main(String[] args) { new WaselApplication().start(); }

    private void start() {
        try {
            Path dataRoot = WaselRuntime.resolveDataRoot(); Files.createDirectories(dataRoot);
            lockChannel = FileChannel.open(dataRoot.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            processLock = lockChannel.tryLock();
            if (processLock == null) { showError("واصل يعمل بالفعل على هذا الجهاز."); lockChannel.close(); lockChannel=null; return; }
            runtime = new WaselRuntime();
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
            browser = cefClient.createBrowser(runtime.startUrl(), false, false);
            SwingUtilities.invokeAndWait(this::createWindow);
        } catch (Throwable error) {
            error.printStackTrace();
            if (runtime != null) runtime.close();
            showError("تعذر تشغيل واصل Java: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            shutdown();
        }
    }

    private void createWindow() {
        frame = new JFrame("واصل - تواصل المنشأة"); frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1000, 680)); frame.setSize(1280, 820); frame.setLocationRelativeTo(null);
        frame.add(browser.getUIComponent());
        frame.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosing(java.awt.event.WindowEvent event) { shutdown(); } });
        frame.setVisible(true);
    }

    private void installFileDialog() {
        cefClient.addDialogHandler(new CefDialogHandler() {
            @Override public boolean onFileDialog(CefBrowser source, FileDialogMode mode, String title, String defaultFilePath,
                                                  Vector<String> acceptFilters, Vector<String> acceptExtensions,
                                                  Vector<String> acceptMimeTypes, CefFileDialogCallback callback) {
                AtomicReference<Vector<String>> chosen = new AtomicReference<>(new Vector<>());
                try {
                    SwingUtilities.invokeAndWait(() -> {
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
                            chosen.set(paths);
                        }
                    });
                    callback.Continue(chosen.get()); return true;
                } catch (Exception ex) { callback.Continue(new Vector<>()); return true; }
            }
        });
    }

    private void installDownloadHandler() {
        cefClient.addDownloadHandler(new CefDownloadHandler() {
            @Override public boolean onBeforeDownload(CefBrowser source, CefDownloadItem item, String suggestedName, CefBeforeDownloadCallback callback) {
                AtomicReference<String> path = new AtomicReference<>("");
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("حفظ الملف المشترك"); chooser.setDialogType(JFileChooser.SAVE_DIALOG);
                        chooser.setSelectedFile(new java.io.File(suggestedName == null || suggestedName.isBlank() ? "shared-file" : suggestedName));
                        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) path.set(chooser.getSelectedFile().getAbsolutePath());
                    });
                } catch (Exception ignored) { }
                if (path.get().isBlank()) callback.Continue("", false);
                else callback.Continue(path.get(), false);
                return true;
            }
            @Override public void onDownloadUpdated(CefBrowser source, CefDownloadItem item, CefDownloadItemCallback callback) { }
        });
    }

    private void installJsDialogHandler() {
        cefClient.addJSDialogHandler(new CefJSDialogHandler() {
            @Override public boolean onJSDialog(CefBrowser source, String origin, JSDialogType type, String message, String defaultPrompt, CefJSDialogCallback callback, BoolRef suppressMessage) {
                AtomicReference<String> response = new AtomicReference<>(); AtomicReference<Boolean> accepted = new AtomicReference<>(false);
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        if (type == JSDialogType.JSDIALOGTYPE_ALERT) { JOptionPane.showMessageDialog(frame, message, "واصل", JOptionPane.INFORMATION_MESSAGE); accepted.set(true); }
                        else if (type == JSDialogType.JSDIALOGTYPE_CONFIRM) accepted.set(JOptionPane.showConfirmDialog(frame, message, "واصل", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
                        else { Object result = JOptionPane.showInputDialog(frame, message, "واصل", JOptionPane.QUESTION_MESSAGE, null, null, defaultPrompt == null ? "" : defaultPrompt); if (result != null) { response.set(result.toString()); accepted.set(true); } }
                    });
                } catch (Exception ignored) { }
                callback.Continue(accepted.get(), response.get()); return true;
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
        if (runtime != null) runtime.close();
        if (browser != null) try { browser.close(true); } catch (Exception ignored) { }
        if (cefClient != null) try { cefClient.dispose(); } catch (Exception ignored) { }
        if (cefApp != null) try { cefApp.dispose(); } catch (Exception ignored) { }
        try { if (processLock != null && processLock.isValid()) processLock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
        if (frame != null) { frame.dispose(); frame = null; }
        System.exit(0);
    }
}
