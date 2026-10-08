package dev.dbviewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {

    @TempDir
    Path dir;

    private Connection seed(Path file) throws Exception {
        Connection c = Db.connect(file, true);
        for (String s : Sql.split("""
                CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT);
                CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id),
                                     total REAL, note TEXT);
                CREATE TABLE settings (k TEXT PRIMARY KEY, v) WITHOUT ROWID;
                CREATE INDEX orders_user ON orders(user_id);
                CREATE VIEW big_orders AS SELECT * FROM orders WHERE total > 100;
                INSERT INTO users VALUES (1, 'ada', 'ada@x.io'), (2, 'bob', NULL);
                INSERT INTO orders VALUES (10, 1, 12.5, 'a;b'), (11, 2, 250, NULL);
                INSERT INTO settings VALUES ('theme', 'dark');
                """)) {
            Sql.exec(c, s);
        }
        return c;
    }

    @Test
    void splitsScripts() {
        List<String> s = Sql.split("""
                SELECT 'a;b'; -- trailing; comment
                /* block; */ SELECT "x;y" FROM t;
                CREATE TRIGGER tr AFTER INSERT ON t BEGIN UPDATE t SET a = 1; DELETE FROM u; END;
                SELECT 2
                """);
        assertEquals(4, s.size());
        assertEquals("SELECT 'a;b'", s.get(0));
        assertTrue(s.get(2).startsWith("CREATE TRIGGER") && s.get(2).endsWith("END"));
        assertEquals("SELECT 2", s.get(3));
    }

    @Test
    void readsReleases() {
        assertTrue(Updater.newer("1.2.0", "1.1.9"));
        assertTrue(Updater.newer("1.0.10", "1.0.9"));
        assertFalse(Updater.newer("1.0.0", "1.0.0"));
        assertFalse(Updater.newer("0.9", "1.0.0"));
        String json = """
                {"html_url": "https://github.com/o/r/releases/tag/v1.2.0", "tag_name": "v1.2.0", "assets": [
                 {"browser_download_url": "https://github.com/o/r/releases/download/v1.2.0/DBViewer-1.2.0-mac-arm64.zip"},
                 {"browser_download_url": "https://github.com/o/r/releases/download/v1.2.0/DBViewer-1.2.0-windows-x64.msi"}]}
                """;
        Updater.Release r = Updater.parse(json, "-windows-x64.msi");
        assertEquals("1.2.0", r.version());
        assertTrue(r.assetUrl().endsWith("DBViewer-1.2.0-windows-x64.msi"));
        assertNull(Updater.parse(json, "-mac-x64.zip").assetUrl());
    }

    @Test
    void loadsSchema() throws Exception {
        Path file = dir.resolve("s.db");
        try (Connection c = seed(file)) {
            Schema schema = Schema.load(c);
            assertEquals(4, schema.tables.size());
            Schema.TableInfo orders = schema.table("orders");
            assertEquals(List.of("id"), orders.pk());
            assertEquals(1, orders.fks().size());
            assertEquals("users", orders.fks().get(0).toTable());
            assertEquals(List.of("id"), orders.fks().get(0).to());
            assertEquals(1, schema.inbound("users").size());
            assertTrue(schema.table("settings").withoutRowid());
            assertFalse(schema.table("settings").hasRowid());
            assertEquals("view", schema.table("big_orders").kind());
            assertEquals(1, orders.indexes().size());
        }
    }

    @Test
    void tracksAndRevertsChanges() throws Exception {
        Path file = dir.resolve("m.db");
        BlockingQueue<Monitor.Tick> ticks = new LinkedBlockingQueue<>();
        List<String> errors = new ArrayList<>();
        try (Connection c = seed(file)) {
            Monitor monitor = new Monitor(file, new Monitor.Listener() {
                @Override
                public void onTick(Monitor.Tick tick) {
                    ticks.add(tick);
                }

                @Override
                public void onMonitorError(Monitor source, String message) {
                    errors.add(message);
                }
            }, Runnable::run);
            monitor.start();
            try {
                Monitor.Tick first = ticks.poll(5, TimeUnit.SECONDS);
                assertNotNull(first, "initial tick " + errors);
                assertTrue(first.schemaChanged());
                assertEquals(2L, first.counts().get("users"));

                Sql.exec(c, "BEGIN");
                Sql.exec(c, "UPDATE users SET email = 'bob@x.io' WHERE id = 2");
                Sql.exec(c, "INSERT INTO users VALUES (3, 'cy', NULL)");
                Sql.exec(c, "DELETE FROM orders WHERE id = 10");
                Sql.exec(c, "UPDATE settings SET v = 'light' WHERE k = 'theme'");
                Sql.exec(c, "COMMIT");

                Monitor.Tick tick = ticks.poll(5, TimeUnit.SECONDS);
                assertNotNull(tick, "change tick " + errors);
                assertEquals(4, tick.changes().size());
                assertEquals(3L, tick.counts().get("users"));
                assertEquals(1L, tick.counts().get("orders"));

                Change upd = tick.changes().stream()
                        .filter(x -> x.table().equals("users") && x.op() == Change.Op.UPDATE).findFirst().orElseThrow();
                assertEquals(1, upd.changed().cardinality());
                assertNull(upd.before()[2]);
                assertEquals("bob@x.io", upd.after()[2]);

                List<Change> newestFirst = new ArrayList<>(tick.changes());
                java.util.Collections.reverse(newestFirst);
                assertEquals(4, Db.revertAll(c, tick.schema(), newestFirst));

                assertEquals(2, Sql.scalarLong(c, "SELECT COUNT(*) FROM users"));
                assertNull(Sql.scalarString(c, "SELECT email FROM users WHERE id = 2"));
                assertEquals("a;b", Sql.scalarString(c, "SELECT note FROM orders WHERE id = 10"));
                assertEquals("dark", Sql.scalarString(c, "SELECT v FROM settings WHERE k = 'theme'"));

                Monitor.Tick reverted = ticks.poll(5, TimeUnit.SECONDS);
                assertNotNull(reverted);
                assertEquals(4, reverted.changes().size());

                Sql.exec(c, "ALTER TABLE users ADD COLUMN age INTEGER");
                Monitor.Tick altered = ticks.poll(5, TimeUnit.SECONDS);
                assertNotNull(altered);
                assertTrue(altered.schemaChanged());
                assertEquals(4, altered.schema().table("users").columns().size());
                assertTrue(errors.isEmpty(), errors.toString());
            } finally {
                monitor.stop();
            }
        }
    }

    @Test
    void snapshotsRoundTrip() throws Exception {
        System.setProperty("dbviewer.snapshots", dir.resolve("snaps").toString());
        Path file = dir.resolve("b.db");
        try (Connection c = seed(file)) {
            Snapshots.Snapshot snap = Snapshots.take(c, file, "before / wipe");
            Sql.exec(c, "DELETE FROM orders");
            Sql.exec(c, "DROP TABLE settings");
            assertEquals(0, Sql.scalarLong(c, "SELECT COUNT(*) FROM orders"));

            assertEquals(1, Snapshots.list(file).size());
            Snapshots.restore(c, snap);
            assertEquals(2, Sql.scalarLong(c, "SELECT COUNT(*) FROM orders"));
            assertEquals("dark", Sql.scalarString(c, "SELECT v FROM settings"));

            Snapshots.delete(snap);
            assertTrue(Snapshots.list(file).isEmpty());
        } finally {
            System.clearProperty("dbviewer.snapshots");
        }
    }
}
