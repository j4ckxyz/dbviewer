package dev.dbviewer;

import org.sqlite.SQLiteConfig;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * An open database. Two connections, each on its own thread: {@code read} serves the grids and
 * is query-only, {@code work} runs the console, edits, reverts and snapshots. Results are
 * handed back on the Swing event thread.
 */
final class Db implements AutoCloseable {

    interface Task<T> {
        T run(Connection c) throws Exception;
    }

    final Path path;
    volatile Schema schema;
    private final Connection read;
    private final Connection work;
    private final ExecutorService readExec = single("db-read");
    private final ExecutorService workExec = single("db-work");

    private Db(Path path, Connection read, Connection work, Schema schema) {
        this.path = path;
        this.read = read;
        this.work = work;
        this.schema = schema;
    }

    static Db open(Path path) throws SQLException {
        Path abs = path.toAbsolutePath();
        Connection read = connect(abs, false);
        try {
            Schema schema = Schema.load(read); // also fails fast if the file is not a database
            Sql.exec(read, "PRAGMA query_only = ON");
            Connection work = connect(abs, true);
            return new Db(abs, read, work, schema);
        } catch (SQLException e) {
            read.close();
            throw e;
        }
    }

    static Connection connect(Path path, boolean foreignKeys) throws SQLException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setBusyTimeout(3000);
        cfg.enforceForeignKeys(foreignKeys);
        return DriverManager.getConnection("jdbc:sqlite:" + path, cfg.toProperties());
    }

    <T> void read(Task<T> task, Consumer<T> ok, Consumer<Exception> err) {
        submit(readExec, read, task, ok, err);
    }

    <T> void work(Task<T> task, Consumer<T> ok, Consumer<Exception> err) {
        submit(workExec, work, task, ok, err);
    }

    private static <T> void submit(ExecutorService exec, Connection c, Task<T> task, Consumer<T> ok,
                                   Consumer<Exception> err) {
        try {
            exec.execute(() -> {
                try {
                    T result = task.run(c);
                    SwingUtilities.invokeLater(() -> ok.accept(result));
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> err.accept(e));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // database was closed while the UI still had work queued
        }
    }

    long fileSize() {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Revert changes in the order given (pass newest first) as one unit. Returns how many
     * actually changed a row.
     */
    static int revertAll(Connection c, Schema schema, List<Change> changes) throws SQLException {
        Sql.exec(c, "SAVEPOINT dbv_revert");
        try {
            int applied = 0;
            for (Change ch : changes) {
                if (ch.revert(c, schema) > 0) applied++;
            }
            Sql.exec(c, "RELEASE dbv_revert");
            return applied;
        } catch (SQLException e) {
            Sql.exec(c, "ROLLBACK TO dbv_revert");
            Sql.exec(c, "RELEASE dbv_revert");
            throw e;
        }
    }

    @Override
    public void close() {
        readExec.shutdownNow();
        workExec.shutdownNow();
        try {
            read.close();
        } catch (SQLException ignored) { }
        try {
            work.close();
        } catch (SQLException ignored) { }
    }

    private static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }
}
