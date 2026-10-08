package dev.dbviewer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable picture of the database structure. */
final class Schema {

    record ColumnInfo(int cid, String name, String type, boolean notNull, String dflt, int pk, int hidden) {
        boolean generated() {
            return hidden == 2 || hidden == 3;
        }
    }

    record ForeignKey(int id, String fromTable, List<String> from, String toTable, List<String> to,
                      String onUpdate, String onDelete) {}

    record IndexInfo(String name, boolean unique, String origin, List<String> columns, String sql) {}

    record TableInfo(String name, String kind, boolean withoutRowid, boolean strict, String sql,
                     List<ColumnInfo> columns, List<ForeignKey> fks, List<IndexInfo> indexes,
                     List<String> triggers) {

        boolean isTable() {
            return kind.equals("table");
        }

        boolean hasRowid() {
            return isTable() && !withoutRowid;
        }

        /** Primary key column names, in key order. */
        List<String> pk() {
            return columns.stream().filter(c -> c.pk() > 0)
                    .sorted(Comparator.comparingInt(ColumnInfo::pk))
                    .map(ColumnInfo::name).toList();
        }

        /** True when single rows can be addressed (edited, deleted, reverted). */
        boolean keyed() {
            return hasRowid() || (isTable() && !pk().isEmpty());
        }

        List<String> columnNames() {
            return columns.stream().map(ColumnInfo::name).toList();
        }

        int columnIndex(String name) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equalsIgnoreCase(name)) return i;
            }
            return -1;
        }

        /** The foreign key that the given column takes part in, or null. */
        ForeignKey fkFor(String column) {
            for (ForeignKey fk : fks) {
                for (String f : fk.from()) if (f.equalsIgnoreCase(column)) return fk;
            }
            return null;
        }
    }

    final List<TableInfo> tables;
    final String sqliteVersion;
    final String journalMode;
    final long schemaVersion;
    private final Map<String, TableInfo> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private Schema(List<TableInfo> tables, String sqliteVersion, String journalMode, long schemaVersion) {
        this.tables = List.copyOf(tables);
        this.sqliteVersion = sqliteVersion;
        this.journalMode = journalMode;
        this.schemaVersion = schemaVersion;
        for (TableInfo t : tables) byName.put(t.name(), t);
    }

    TableInfo table(String name) {
        return byName.get(name);
    }

    /** Foreign keys in other tables (or the same one) that point at {@code table}. */
    List<ForeignKey> inbound(String table) {
        List<ForeignKey> out = new ArrayList<>();
        for (TableInfo t : tables) {
            for (ForeignKey fk : t.fks()) if (fk.toTable().equalsIgnoreCase(table)) out.add(fk);
        }
        return out;
    }

    static Schema load(Connection c) throws SQLException {
        // name -> {kind, withoutRowid, strict}
        Map<String, Object[]> meta = new HashMap<>();
        for (Object[] r : Sql.rows(c, "PRAGMA main.table_list")) {
            if (!"main".equals(r[0])) continue;
            meta.put((String) r[1], new Object[] {r[2], num(r[4]) != 0, num(r[5]) != 0});
        }

        Map<String, String> ddl = new HashMap<>();
        Map<String, String> indexSql = new HashMap<>();
        Map<String, List<String>> triggers = new HashMap<>();
        for (Object[] r : Sql.rows(c, "SELECT type, name, tbl_name, sql FROM main.sqlite_schema")) {
            String type = (String) r[0];
            String sql = (String) r[3];
            switch (type) {
                case "table", "view" -> ddl.put((String) r[1], sql);
                case "index" -> indexSql.put((String) r[1], sql);
                case "trigger" -> triggers.computeIfAbsent((String) r[2], k -> new ArrayList<>()).add(sql);
                default -> { }
            }
        }

        List<TableInfo> tables = new ArrayList<>();
        for (var e : meta.entrySet()) {
            String name = e.getKey();
            String kind = (String) e.getValue()[0];
            if (name.startsWith("sqlite_") || kind.equals("shadow")) continue;

            List<ColumnInfo> cols = new ArrayList<>();
            for (Object[] r : Sql.rows(c, "PRAGMA main.table_xinfo(" + Sql.q(name) + ")")) {
                int hidden = (int) num(r[6]);
                if (hidden == 1) continue; // hidden virtual-table column, not part of SELECT *
                cols.add(new ColumnInfo((int) num(r[0]), (String) r[1], r[2] == null ? "" : r[2].toString(),
                        num(r[3]) != 0, r[4] == null ? null : r[4].toString(), (int) num(r[5]), hidden));
            }

            Map<Integer, ForeignKey> fks = new TreeMap<>();
            if (kind.equals("table")) {
                for (Object[] r : Sql.rows(c, "PRAGMA main.foreign_key_list(" + Sql.q(name) + ")")) {
                    int id = (int) num(r[0]);
                    ForeignKey fk = fks.computeIfAbsent(id, k -> new ForeignKey(id, name, new ArrayList<>(),
                            (String) r[2], new ArrayList<>(), (String) r[5], (String) r[6]));
                    fk.from().add((String) r[3]);
                    if (r[4] != null) fk.to().add((String) r[4]);
                }
            }

            List<IndexInfo> indexes = new ArrayList<>();
            if (kind.equals("table")) {
                for (Object[] r : Sql.rows(c, "PRAGMA main.index_list(" + Sql.q(name) + ")")) {
                    String idx = (String) r[1];
                    List<String> icols = new ArrayList<>();
                    for (Object[] ic : Sql.rows(c, "PRAGMA main.index_info(" + Sql.q(idx) + ")")) {
                        icols.add(ic[2] == null ? "<expr>" : (String) ic[2]);
                    }
                    indexes.add(new IndexInfo(idx, num(r[2]) != 0, (String) r[3], icols, indexSql.get(idx)));
                }
            }

            tables.add(new TableInfo(name, kind, (boolean) e.getValue()[1], (boolean) e.getValue()[2],
                    ddl.get(name), List.copyOf(cols), new ArrayList<>(fks.values()), List.copyOf(indexes),
                    List.copyOf(triggers.getOrDefault(name, List.of()))));
        }
        tables.sort(Comparator.comparing(TableInfo::name, String.CASE_INSENSITIVE_ORDER));

        // "REFERENCES parent" without a column list means the parent's primary key.
        Map<String, TableInfo> lookup = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TableInfo t : tables) lookup.put(t.name(), t);
        for (TableInfo t : tables) {
            for (ForeignKey fk : t.fks()) {
                TableInfo parent = lookup.get(fk.toTable());
                if (fk.to().isEmpty() && parent != null) fk.to().addAll(parent.pk());
            }
        }

        return new Schema(tables,
                Sql.scalarString(c, "SELECT sqlite_version()"),
                Sql.scalarString(c, "PRAGMA main.journal_mode"),
                Sql.scalarLong(c, "PRAGMA main.schema_version"));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
