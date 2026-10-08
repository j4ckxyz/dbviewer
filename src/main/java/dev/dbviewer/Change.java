package dev.dbviewer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * One observed row change. {@code before}/{@code after} line up with {@code cols};
 * {@code key} addresses the row (rowid, or primary key for WITHOUT ROWID tables).
 */
record Change(long seq, long time, String table, Op op, boolean rowidKey, List<String> keyCols,
              Object[] key, List<String> cols, Object[] before, Object[] after, String note) {

    enum Op { INSERT, UPDATE, DELETE, INFO }

    static Change info(long seq, String table, String note) {
        return new Change(seq, System.currentTimeMillis(), table, Op.INFO, false, List.of(), null,
                List.of(), null, null, note);
    }

    boolean revertable() {
        return op != Op.INFO && key != null;
    }

    /** Indexes of columns whose value differs between before and after. */
    BitSet changed() {
        BitSet b = new BitSet();
        if (op != Op.UPDATE) return b;
        for (int i = 0; i < cols.size(); i++) {
            if (!same(before[i], after[i])) b.set(i);
        }
        return b;
    }

    String keyText() {
        if (key == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length; i++) {
            if (i > 0) sb.append(", ");
            if (!rowidKey) sb.append(keyCols.get(i)).append('=');
            sb.append(Format.cell(key[i], 40));
        }
        return sb.toString();
    }

    String summary() {
        if (op == Op.INFO) return note;
        StringBuilder sb = new StringBuilder();
        if (op == Op.UPDATE) {
            BitSet ch = changed();
            for (int i = ch.nextSetBit(0); i >= 0; i = ch.nextSetBit(i + 1)) {
                if (sb.length() > 0) sb.append("   ");
                sb.append(cols.get(i)).append(": ").append(Format.cell(before[i], 40))
                        .append(" → ").append(Format.cell(after[i], 40));
                if (sb.length() > 300) break;
            }
        } else {
            Object[] row = op == Op.INSERT ? after : before;
            for (int i = 0; i < cols.size() && sb.length() < 300; i++) {
                if (i > 0) sb.append("   ");
                sb.append(cols.get(i)).append('=').append(Format.cell(row[i], 40));
            }
        }
        return sb.toString();
    }

    /** Filter expression selecting this row in its table. */
    String whereLiteral() {
        if (key == null) return "";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < key.length; i++) {
            parts.add(rowidKey ? "rowid = " + Sql.literal(key[i]) : Sql.equalsExpr(keyCols.get(i), key[i]));
        }
        return String.join(" AND ", parts);
    }

    private String keyWhere() {
        if (rowidKey) return "rowid = ?";
        List<String> parts = new ArrayList<>();
        for (String k : keyCols) parts.add(Sql.q(k) + " = ?");
        return String.join(" AND ", parts);
    }

    /**
     * Apply the inverse of this change. Returns the number of rows affected; 0 means the row
     * has moved on since (already gone, or already back).
     */
    int revert(Connection c, Schema schema) throws SQLException {
        if (!revertable()) return 0;
        Schema.TableInfo t = schema.table(table);
        if (t == null) throw new SQLException("Table " + table + " no longer exists");
        String tq = "main." + Sql.q(table);
        switch (op) {
            case INSERT -> {
                return Sql.update(c, "DELETE FROM " + tq + " WHERE " + keyWhere(), key);
            }
            case DELETE -> {
                List<String> names = new ArrayList<>();
                List<Object> values = new ArrayList<>();
                if (rowidKey) {
                    names.add("rowid");
                    values.add(key[0]);
                }
                for (int i = 0; i < cols.size(); i++) {
                    if (!writable(t, cols.get(i))) continue;
                    names.add(Sql.q(cols.get(i)));
                    values.add(before[i]);
                }
                String marks = String.join(", ", names.stream().map(n -> "?").toList());
                return Sql.update(c, "INSERT INTO " + tq + " (" + String.join(", ", names) + ") VALUES ("
                        + marks + ")", values.toArray());
            }
            case UPDATE -> {
                List<String> sets = new ArrayList<>();
                List<Object> values = new ArrayList<>();
                BitSet ch = changed();
                for (int i = ch.nextSetBit(0); i >= 0; i = ch.nextSetBit(i + 1)) {
                    if (!writable(t, cols.get(i))) continue;
                    sets.add(Sql.q(cols.get(i)) + " = ?");
                    values.add(before[i]);
                }
                if (sets.isEmpty()) return 0;
                values.addAll(Arrays.asList(key));
                return Sql.update(c, "UPDATE " + tq + " SET " + String.join(", ", sets) + " WHERE "
                        + keyWhere(), values.toArray());
            }
            default -> {
                return 0;
            }
        }
    }

    private static boolean writable(Schema.TableInfo t, String col) {
        int i = t.columnIndex(col);
        return i >= 0 && !t.columns().get(i).generated();
    }

    static boolean same(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        if (a instanceof Number x && b instanceof Number y) {
            boolean fa = a instanceof Double || a instanceof Float;
            boolean fb = b instanceof Double || b instanceof Float;
            return fa == fb && (fa ? x.doubleValue() == y.doubleValue() : x.longValue() == y.longValue());
        }
        return Objects.equals(a, b);
    }
}
