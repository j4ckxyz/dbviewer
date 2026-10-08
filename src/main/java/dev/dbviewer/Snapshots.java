package dev.dbviewer;

import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Point-in-time copies of a database, made and restored with SQLite's online backup API so they
 * are consistent even while another process is writing. Stored outside the project, per database.
 */
final class Snapshots {
    private Snapshots() {}

    record Snapshot(Path file, String name, long time, long size) {}

    static Path appDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "DBViewer");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null ? Path.of(appData, "DBViewer") : Path.of(home, "AppData", "Roaming", "DBViewer");
        }
        return Path.of(home, ".local", "share", "dbviewer");
    }

    static Path dirFor(Path db) {
        String override = System.getProperty("dbviewer.snapshots");
        Path root = override != null ? Path.of(override) : appDir().resolve("snapshots");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest(db.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(hash, 0, 8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Snapshot> list(Path db) throws IOException {
        Path dir = dirFor(db);
        List<Snapshot> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String fn = f.getFileName().toString();
                int us = fn.indexOf('_');
                if (!fn.endsWith(".sqlite") || us <= 0) continue;
                try {
                    long time = Long.parseLong(fn.substring(0, us));
                    String name = fn.substring(us + 1, fn.length() - ".sqlite".length());
                    out.add(new Snapshot(f, name, time, Files.size(f)));
                } catch (NumberFormatException ignored) { }
            }
        }
        out.sort(Comparator.comparingLong(Snapshot::time).reversed());
        return out;
    }

    static Snapshot take(Connection c, Path db, String name) throws SQLException, IOException {
        Path dir = dirFor(db);
        Files.createDirectories(dir);
        long time = System.currentTimeMillis();
        String safe = name.strip().replaceAll("[^\\p{L}\\p{N} ._-]", "-");
        if (safe.isEmpty()) safe = "snapshot";
        Path file = dir.resolve(time + "_" + safe + ".sqlite");
        int rc = c.unwrap(SQLiteConnection.class).getDatabase().backup("main", file.toString(), null);
        if (rc != 0) {
            Files.deleteIfExists(file);
            throw new SQLException("Snapshot failed (SQLite code " + rc + ")");
        }
        return new Snapshot(file, safe, time, Files.size(file));
    }

    /** Replace the live database contents with the snapshot. The caller confirms first. */
    static void restore(Connection c, Snapshot snapshot) throws SQLException {
        int rc = c.unwrap(SQLiteConnection.class).getDatabase().restore("main", snapshot.file().toString(), null);
        if (rc != 0) throw new SQLException("Restore failed (SQLite code " + rc + ")");
    }

    static void delete(Snapshot snapshot) throws IOException {
        Files.deleteIfExists(snapshot.file());
    }
}
