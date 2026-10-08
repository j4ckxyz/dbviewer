package dev.dbviewer;

import dev.dbviewer.Schema.TableInfo;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches a database for commits made by any process and turns them into row-level changes.
 *
 * <p>It polls {@code PRAGMA data_version} (a header read, no table access) on its own
 * connection. When the version moves it diffs each tracked table against a private shadow copy
 * kept in a temp database attached to that connection, so nothing is ever written to the
 * user's file. Tables past the size limits are not shadowed; only their row counts are watched.
 */
final class Monitor {

    record Tick(Monitor source, Schema schema, boolean schemaChanged, List<Change> changes,
                Map<String, Long> counts, Set<String> untracked, long tookMs) {}

    interface Listener {
        void onTick(Tick tick);

        void onMonitorError(Monitor source, String message);
    }

    static final long MAX_TABLE_ROWS = 200_000;
    static final long MAX_TOTAL_ROWS = 1_500_000;
    static final int MAX_LISTED = 500;
    private static final long BASE_DELAY_MS = 150;
    private static final AtomicLong SEQ = new AtomicLong();

    private record Tracked(TableInfo table, List<String> cols, boolean rowid, List<String> keyCols,
                           String deleted, String inserted, String updated, String clear, String fill) {}

    private final Path path;
    private final Listener listener;
    private final Executor deliver;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean paused;

    private Connection conn;
    private Schema schema;
    private boolean needRebuild = true;
    private final Map<String, Tracked> tracked = new LinkedHashMap<>();
    private final Map<String, Long> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> untracked = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    Monitor(Path path, Listener listener, Executor deliver) {
        this.path = path;
        this.listener = listener;
        this.deliver = deliver;
        this.thread = new Thread(this::run, "db-monitor");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void setPaused(boolean paused) {
        this.paused = paused;
    }

    boolean isPaused() {
        return paused;
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long delay = 0;
        long seen = Long.MIN_VALUE;
        try {
            conn = Db.connect(path, false);
            Sql.exec(conn, "ATTACH DATABASE '' AS _dbv");
            while (running) {
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        continue;
                    }
                }
                delay = BASE_DELAY_MS;
                if (paused) continue;
                try {
                    long version = Sql.scalarLong(conn, "PRAGMA main.data_version");
                    if (version == seen && !needRebuild) continue;
                    long t0 = System.nanoTime();
                    Tick tick = step(t0);
                    seen = version;
                    // Back off on big databases so diffing never hogs the file.
                    delay = Math.max(BASE_DELAY_MS, Math.min(5000, tick.tookMs() * 4));
                    deliver.execute(() -> listener.onTick(tick));
                } catch (SQLException e) {
                    needRebuild = true;
                    delay = 1000;
                    String msg = e.getMessage();
                    deliver.execute(() -> listener.onMonitorError(this, msg));
                }
            }
        } catch (SQLException e) {
            String msg = e.getMessage();
            deliver.execute(() -> listener.onMonitorError(this, msg));
        } finally {
            try {
                if (conn != null) conn.close();
            } catch (SQLException ignored) { }
        }
    }

    private Tick step(long t0) throws SQLException {
        List<Change> changes = new ArrayList<>();
        boolean schemaChanged = false;

        if (!needRebuild && Sql.scalarLong(conn, "PRAGMA main.schema_version") != schema.schemaVersion) {
            needRebuild = true;
            changes.add(Change.info(SEQ.incrementAndGet(), "", "schema changed"));
        }

        if (needRebuild) {
            if (!conn.getAutoCommit()) conn.setAutoCommit(true);
            Sql.exec(conn, "DETACH DATABASE _dbv");
            Sql.exec(conn, "ATTACH DATABASE '' AS _dbv");
            Sql.exec(conn, "PRAGMA _dbv.journal_mode = MEMORY");
            Sql.exec(conn, "PRAGMA _dbv.synchronous = OFF");
            schema = Schema.load(conn);
            inTransaction(this::rebuild);
            needRebuild = false;
            schemaChanged = true;
        } else {
            inTransaction(() -> diff(changes));
        }

        long took = (System.nanoTime() - t0) / 1_000_000;
        return new Tick(this, schema, schemaChanged, List.copyOf(changes), new TreeMap<>(counts),
                new TreeSet<>(untracked), took);
    }

    private interface Body {
        void run() throws SQLException;
    }

    /** One read snapshot of the user's file for the whole step. */
    private void inTransaction(Body body) throws SQLException {
        Sql.exec(conn, "BEGIN");
        try {
            body.run();
            Sql.exec(conn, "COMMIT");
        } catch (SQLException e) {
            try {
                Sql.exec(conn, "ROLLBACK");
            } catch (SQLException ignored) { }
            throw e;
        }
    }

    private void rebuild() throws SQLException {
        tracked.clear();
        counts.clear();
        untracked.clear();

        List<TableInfo> tables = new ArrayList<>();
        for (TableInfo t : schema.tables) {
            if (!t.isTable()) continue;
            counts.put(t.name(), Sql.scalarLong(conn, "SELECT COUNT(*) FROM main." + Sql.q(t.name())));
            tables.add(t);
        }
        tables.sort(Comparator.comparingLong(t -> counts.get(t.name())));

        long total = 0;
        for (TableInfo t : tables) {
            long n = counts.get(t.name());
            if (!t.keyed() || t.columns().isEmpty() || n > MAX_TABLE_ROWS || total + n > MAX_TOTAL_ROWS) {
                untracked.add(t.name());
                continue;
            }
            total += n;
            Tracked tr = plan(t);
            Sql.exec(conn, createShadow(tr));
            Sql.exec(conn, tr.fill());
            tracked.put(t.name(), tr);
        }
    }

    private static String createShadow(Tracked tr) {
        String sh = "_dbv." + Sql.q(tr.table().name());
        String cols = Sql.qList(tr.cols());
        return tr.rowid()
                ? "CREATE TABLE " + sh + " (__rid INTEGER PRIMARY KEY, " + cols + ")"
                : "CREATE TABLE " + sh + " (" + cols + ", PRIMARY KEY (" + Sql.qList(tr.keyCols())
                        + ")) WITHOUT ROWID";
    }

    private static Tracked plan(TableInfo t) {
        String main = "main." + Sql.q(t.name());
        String sh = "_dbv." + Sql.q(t.name());
        List<String> cols = t.columnNames();
        boolean rowid = t.hasRowid();
        List<String> keyCols = rowid ? List.of("rowid") : t.pk();

        String join;
        if (rowid) {
            join = "m.rowid = s.__rid";
        } else {
            List<String> parts = new ArrayList<>();
            for (String k : keyCols) parts.add("m." + Sql.q(k) + " = s." + Sql.q(k));
            join = String.join(" AND ", parts);
        }

        String sCols = prefixed("s", cols);
        String mCols = prefixed("m", cols);
        String sLead = rowid ? "s.__rid, " : "";
        String mLead = rowid ? "m.rowid, " : "";

        List<String> diffs = new ArrayList<>();
        for (String c : cols) diffs.add("s." + Sql.q(c) + " IS NOT m." + Sql.q(c));

        String deleted = "SELECT " + sLead + sCols + " FROM " + sh + " s WHERE NOT EXISTS (SELECT 1 FROM "
                + main + " m WHERE " + join + ") LIMIT " + (MAX_LISTED + 1);
        String inserted = "SELECT " + mLead + mCols + " FROM " + main + " m WHERE NOT EXISTS (SELECT 1 FROM "
                + sh + " s WHERE " + join + ") LIMIT " + (MAX_LISTED + 1);
        String updated = "SELECT " + sLead + sCols + ", " + mCols + " FROM " + sh + " s JOIN " + main
                + " m ON " + join + " WHERE " + String.join(" OR ", diffs) + " LIMIT " + (MAX_LISTED + 1);
        String fill = "INSERT INTO " + sh + " SELECT " + (rowid ? "rowid, " : "") + Sql.qList(cols)
                + " FROM " + main;
        return new Tracked(t, cols, rowid, keyCols, deleted, inserted, updated, "DELETE FROM " + sh, fill);
    }

    private static String prefixed(String alias, List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (String c : cols) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(alias).append('.').append(Sql.q(c));
        }
        return sb.toString();
    }

    private void diff(List<Change> out) throws SQLException {
        long now = System.currentTimeMillis();
        for (Tracked tr : tracked.values()) {
            String name = tr.table().name();
            int n = tr.cols().size();
            int off = tr.rowid() ? 1 : 0;
            int before = out.size();

            Object[][] del = Sql.rows(conn, tr.deleted());
            for (Object[] r : limit(del, name, "deleted", out)) {
                Object[] row = Arrays.copyOfRange(r, off, off + n);
                out.add(change(tr, now, Change.Op.DELETE, key(tr, r, row), row, null));
            }
            Object[][] upd = Sql.rows(conn, tr.updated());
            for (Object[] r : limit(upd, name, "updated", out)) {
                Object[] old = Arrays.copyOfRange(r, off, off + n);
                Object[] cur = Arrays.copyOfRange(r, off + n, off + 2 * n);
                out.add(change(tr, now, Change.Op.UPDATE, key(tr, r, old), old, cur));
            }
            Object[][] ins = Sql.rows(conn, tr.inserted());
            for (Object[] r : limit(ins, name, "inserted", out)) {
                Object[] row = Arrays.copyOfRange(r, off, off + n);
                out.add(change(tr, now, Change.Op.INSERT, key(tr, r, row), null, row));
            }

            if (out.size() > before) {
                Sql.exec(conn, tr.clear());
                Sql.exec(conn, tr.fill());
                counts.put(name, Sql.scalarLong(conn, "SELECT COUNT(*) FROM main." + Sql.q(name)));
            }
        }

        for (String name : untracked) {
            long was = counts.getOrDefault(name, 0L);
            long is = Sql.scalarLong(conn, "SELECT COUNT(*) FROM main." + Sql.q(name));
            if (is != was) {
                counts.put(name, is);
                out.add(Change.info(SEQ.incrementAndGet(), name, "row count " + Format.count(was) + " → "
                        + Format.count(is) + " (table too large for row tracking)"));
            }
        }
    }

    private static Object[][] limit(Object[][] rows, String table, String what, List<Change> out) {
        if (rows.length <= MAX_LISTED) return rows;
        out.add(Change.info(SEQ.incrementAndGet(), table, "more than " + MAX_LISTED + " rows " + what
                + " at once; only the first " + MAX_LISTED + " are listed"));
        return Arrays.copyOf(rows, MAX_LISTED);
    }

    private static Object[] key(Tracked tr, Object[] raw, Object[] row) {
        if (tr.rowid()) return new Object[] {raw[0]};
        Object[] key = new Object[tr.keyCols().size()];
        for (int i = 0; i < key.length; i++) key[i] = row[tr.table().columnIndex(tr.keyCols().get(i))];
        return key;
    }

    private static Change change(Tracked tr, long time, Change.Op op, Object[] key, Object[] before,
                                 Object[] after) {
        return new Change(SEQ.incrementAndGet(), time, tr.table().name(), op, tr.rowid(), tr.keyCols(), key,
                tr.cols(), before, after, null);
    }
}
