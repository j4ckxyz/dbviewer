package dev.dbviewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.function.IntConsumer;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from GitHub Releases. Checks the latest release of the repository this build
 * came from, downloads the asset for this platform over HTTPS, and swaps it in once the app
 * has exited.
 */
final class Updater {
    private Updater() {}

    record Release(String version, String assetUrl, String pageUrl) {}

    static final String VERSION;
    static final String REPO;
    private static final Preferences PREFS = Preferences.userNodeForPackage(Updater.class);
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    static {
        Properties p = new Properties();
        try (InputStream in = Updater.class.getResourceAsStream("/app.properties")) {
            if (in != null) p.load(in);
        } catch (IOException ignored) { }
        VERSION = p.getProperty("version", "0");
        REPO = p.getProperty("repo", "");
    }

    static boolean auto() {
        return PREFS.getBoolean("autoUpdateCheck", true);
    }

    static void setAuto(boolean on) {
        PREFS.putBoolean("autoUpdateCheck", on);
    }

    /** Path of the installed launcher, or null when running from source. */
    private static String appPath() {
        return System.getProperty("jpackage.app-path");
    }

    static boolean canInstall() {
        return appPath() != null && (OS.contains("mac") || OS.contains("win"));
    }

    /** The release asset this platform installs from. */
    static String assetSuffix() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        if (OS.contains("mac")) return "-mac-" + (arm ? "arm64" : "x64") + ".zip";
        if (OS.contains("win")) return "-windows-" + (arm ? "arm64" : "x64") + ".msi";
        return null;
    }

    /** Latest published release, or null if there is none. Blocking; call off the UI thread. */
    static Release latest() throws IOException {
        HttpURLConnection c = open("https://api.github.com/repos/" + REPO + "/releases/latest");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) return null;
        if (code != 200) throw new IOException("GitHub answered " + code);
        try (InputStream in = c.getInputStream()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), assetSuffix());
        }
    }

    static Release parse(String json, String suffix) {
        Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(json);
        if (!tag.find()) return null;
        Matcher page = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"").matcher(json);
        String asset = null;
        Matcher urls = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (suffix != null && urls.find()) {
            if (urls.group(1).endsWith(suffix)) asset = urls.group(1);
        }
        return new Release(tag.group(1), asset, page.find() ? page.group(1) : "https://github.com/" + REPO + "/releases");
    }

    /** True when {@code candidate} is a higher dotted version than {@code current}. */
    static boolean newer(String candidate, String current) {
        String[] a = candidate.split("[.\\-+]"), b = current.split("[.\\-+]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = part(a, i), y = part(b, i);
            if (x != y) return x > y;
        }
        return false;
    }

    private static int part(String[] parts, int i) {
        try {
            return i < parts.length ? Integer.parseInt(parts[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Download the release and start a helper that replaces the installed app as soon as this
     * process exits, then relaunches it. The caller must quit the app when this returns.
     */
    static void install(Release release, IntConsumer progress) throws Exception {
        if (!canInstall()) throw new IOException("Updates can only be installed by the packaged app.");
        if (release.assetUrl() == null) throw new IOException("This release has no download for this platform.");
        if (!release.assetUrl().startsWith("https://github.com/" + REPO + "/")) {
            throw new IOException("Unexpected download location: " + release.assetUrl());
        }
        Path launcher = Path.of(appPath());
        Path tmp = Files.createTempDirectory("dbviewer-update");
        String pid = Long.toString(ProcessHandle.current().pid());

        if (OS.contains("mac")) {
            Path bundle = launcher.getParent().getParent().getParent(); // DBViewer.app/Contents/MacOS/DBViewer
            if (!bundle.getFileName().toString().endsWith(".app") || !Files.isWritable(bundle.getParent())) {
                throw new IOException("No permission to replace " + bundle + ". Download the update from the release page instead.");
            }
            Path zip = tmp.resolve("update.zip");
            download(release.assetUrl(), zip, progress);
            Path unpacked = tmp.resolve("unpacked");
            run("/usr/bin/ditto", "-x", "-k", zip.toString(), unpacked.toString());
            Path fresh = unpacked.resolve(bundle.getFileName());
            if (!Files.isDirectory(fresh)) throw new IOException("Update archive did not contain " + bundle.getFileName());

            Path script = tmp.resolve("swap.sh");
            Files.writeString(script, """
                    #!/bin/sh
                    # $1 pid to wait for, $2 installed app, $3 new app
                    while kill -0 "$1" 2>/dev/null; do sleep 0.2; done
                    rm -rf "$2.old"
                    if mv "$2" "$2.old" && mv "$3" "$2"; then
                      rm -rf "$2.old"
                    else
                      [ -d "$2" ] || mv "$2.old" "$2"
                    fi
                    open "$2"
                    """);
            detached("/usr/bin/nohup", "/bin/sh", script.toString(), pid, bundle.toString(), fresh.toString());
        } else {
            Path msi = tmp.resolve("update.msi");
            download(release.assetUrl(), msi, progress);
            Path script = tmp.resolve("update.cmd");
            Files.writeString(script, """
                    @echo off
                    rem %1 pid to wait for, %2 installer, %3 app to relaunch
                    :wait
                    tasklist /FI "PID eq %1" 2>NUL | find "%1" >NUL
                    if not errorlevel 1 (
                      timeout /t 1 /nobreak >NUL
                      goto wait
                    )
                    msiexec /i "%~2" /passive /norestart
                    start "" "%~3"
                    """.replace("\n", "\r\n"));
            detached("cmd", "/c", "start", "", "/min", script.toString(), pid, msi.toString(), launcher.toString());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "DBViewer/" + VERSION);
        return c;
    }

    private static void download(String url, Path to, IntConsumer progress) throws IOException {
        HttpURLConnection c = open(url);
        if (c.getResponseCode() != 200) throw new IOException("Download failed (" + c.getResponseCode() + ")");
        long total = c.getContentLengthLong();
        long done = 0;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(to)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) progress.accept((int) (done * 100 / total));
            }
        }
        if (total > 0 && done != total) throw new IOException("Download was cut short");
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (p.waitFor() != 0) throw new IOException(cmd[0] + " failed");
    }

    private static void detached(String... cmd) throws IOException {
        new ProcessBuilder(cmd)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(OS.contains("win") ? "NUL" : "/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
