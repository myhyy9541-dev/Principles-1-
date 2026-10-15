package com.wasel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class AutoStart {
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private AutoStart() { }

    public static void sync(boolean enabled) throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        if (enabled) {
            String launcher = System.getProperty("jpackage.app-path", "");
            if (launcher.isBlank()) launcher = ProcessHandle.current().info().command().orElse("");
            if (launcher.isBlank() || !Files.isRegularFile(Path.of(launcher)) || !launcher.toLowerCase(Locale.ROOT).endsWith(".exe"))
                throw new IOException("خيار بدء Windows متاح بعد تثبيت نسخة واصل؛ شغّل التطبيق من ملف EXE المثبّت.");
            run("reg.exe", "add", RUN_KEY, "/v", "WaselLAN", "/t", "REG_SZ", "/d", "\"" + launcher + "\"", "/f");
        } else {
            try { run("reg.exe", "delete", RUN_KEY, "/v", "WaselLAN", "/f"); }
            catch (IOException ignored) { }
        }
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("انتهت مهلة تحديث إعداد بدء Windows."); }
        if (process.exitValue() != 0) throw new IOException("تعذر تحديث إعداد بدء Windows: " + output.strip());
    }
}
