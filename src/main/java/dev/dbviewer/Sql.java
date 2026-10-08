package dev.dbviewer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SQL text helpers and tiny JDBC conveniences. */
final class Sql {
    private Sql() {}

    /** Quote an identifier. */
    static String q(String ident) {
        return '"' + ident.replace("\"", "\"\"") + '"';
    }

    static String qList(List<String> idents) {
        StringBuilder sb = new StringBuilder();
        for (String s : idents) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(q(s));
        }
        return sb.toString();
    }

    /** Render a value as a SQL literal, for building filters the user can read and edit. */
    static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return v.toString();
        if (v instanceof byte[] b) {
            StringBuilder sb = new StringBuilder("x'");
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.append('\'').toString();
        }
        return '\'' + v.toString().replace("'", "''") + '\'';
    }

    /** {@code col = literal}, or {@code col IS NULL}. */
    static String equalsExpr(String column, Object v) {
        return v == null ? q(column) + " IS NULL" : q(column) + " = " + literal(v);
    }

    static Object[][] rows(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                List<Object[]> out = new ArrayList<>();
                while (rs.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                    out.add(row);
                }
                return out.toArray(new Object[0][]);
            }
        }
    }

    static long scalarLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    static String scalarString(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Split a script into statements. Semicolons inside strings, quoted identifiers and comments
     * are ignored, and a CREATE TRIGGER body is kept together up to its closing END.
     */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        for (int i = 0; i < n; i++) {
            char ch = script.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') {
                int j = i + 1;
                while (j < n) {
                    if (script.charAt(j) == ch) {
                        if (j + 1 < n && script.charAt(j + 1) == ch) j++;
                        else break;
                    }
                    j++;
                }
                cur.append(script, i, Math.min(j + 1, n));
                i = j;
            } else if (ch == '[') {
                int j = script.indexOf(']', i);
                if (j < 0) j = n - 1;
                cur.append(script, i, j + 1);
                i = j;
            } else if (ch == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int j = script.indexOf('\n', i);
                if (j < 0) j = n;
                cur.append(script, i, j);
                i = j - 1;
            } else if (ch == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int j = script.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                cur.append(script, i, j);
                i = j - 1;
            } else if (ch == ';') {
                if (isOpenTrigger(cur)) {
                    cur.append(ch);
                } else {
                    addStatement(out, cur);
                }
            } else {
                cur.append(ch);
            }
        }
        addStatement(out, cur);
        return out;
    }

    private static void addStatement(List<String> out, StringBuilder cur) {
        String s = cur.toString().strip();
        cur.setLength(0);
        if (!stripComments(s).isBlank()) out.add(s);
    }

    private static boolean isOpenTrigger(StringBuilder cur) {
        String s = stripComments(cur.toString()).strip().toUpperCase(Locale.ROOT);
        if (!s.matches("(?s)^CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b.*")) return false;
        return !s.matches("(?s).*\\bEND$");
    }

    static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }
}
