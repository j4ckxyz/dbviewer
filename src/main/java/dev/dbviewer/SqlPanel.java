package dev.dbviewer;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SQL console: run a script or the selection against the open database. */
final class SqlPanel extends JPanel {

    private static final int MAX_ROWS = 50_000;
    private static final String HELP = """
            Dot-commands:
              .tables              list tables and views
              .schema [table]      show CREATE statements
              .indexes [table]     list indexes
              .databases           list attached databases
              .help                this text
            Anything else is sent to SQLite as SQL. Separate statements with ';'.""";

    private record Outcome(String[] columns, List<Object[]> rows, boolean truncated, String log, boolean failed,
                           long millis) {}

    private Db db;
    private final MainWindow win;
    private final RSyntaxTextArea editor = new RSyntaxTextArea(10, 80);
    private final RTextScrollPane editorScroll = new RTextScrollPane(editor);
    private final ResultModel model = new ResultModel();
    private final JTable result = new JTable(model);
    private final JTextArea messages = new JTextArea();
    private final JTabbedPane lower = new JTabbedPane();
    private final JLabel info = new JLabel(" ");
    private final JButton run = new JButton("Run");
    private final JButton stop = new JButton("Stop");
    private volatile Statement current;

    SqlPanel(MainWindow win) {
        super(new BorderLayout());
        this.win = win;

        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        editor.setFont(Theme.mono);
        editor.setTabSize(2);
        editor.setTabsEmulated(true);
        editor.setAntiAliasingEnabled(true);
        editor.setText("-- " + shortcut() + "+Enter runs the selection, or everything if nothing is selected.\n"
                + "-- Try .tables or .help\n\nSELECT name, type FROM sqlite_schema ORDER BY name;\n");
        editorScroll.setBorder(Theme.line(1, 0, 0, 0));
        editorScroll.getGutter().setLineNumberFont(Theme.mono.deriveFont(11f));

        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask), "run-sql");
        editor.getActionMap().put("run-sql", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                execute();
            }
        });

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        run.setFocusable(false);
        run.setToolTipText("Run selection or whole script (" + shortcut() + "+Enter)");
        run.addActionListener(e -> execute());
        stop.setFocusable(false);
        stop.setEnabled(false);
        stop.addActionListener(e -> {
            Statement st = current;
            if (st != null) {
                try {
                    st.cancel();
                } catch (SQLException ignored) { }
            }
        });
        bar.add(run);
        bar.add(stop);
        bar.add(Box.createHorizontalStrut(10));
        info.setFont(Theme.mono);
        bar.add(info);

        result.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        Theme.dense(result);
        result.setDefaultRenderer(Object.class, new ValueRenderer());
        result.getTableHeader().setFont(Theme.mono);
        JScrollPane resultScroll = new JScrollPane(result);
        resultScroll.setBorder(BorderFactory.createEmptyBorder());

        messages.setEditable(false);
        messages.setFont(Theme.mono);
        messages.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane messageScroll = new JScrollPane(messages);
        messageScroll.setBorder(BorderFactory.createEmptyBorder());

        lower.addTab("Result", resultScroll);
        lower.addTab("Messages", messageScroll);

        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(editorScroll, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, lower);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split);

        applyTheme();
        Theme.onChange(this::applyTheme);
    }

    void setDb(Db db) {
        this.db = db;
    }

    private static String shortcut() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "⌘" : "Ctrl";
    }

    private void applyTheme() {
        editor.setBackground(Theme.surface);
        editor.setForeground(Theme.text);
        editor.setCaretColor(Theme.text);
        editor.setCurrentLineHighlightColor(Theme.blend(Theme.surface, Theme.text, 0.05f));
        editor.setSelectionColor(Theme.blend(Theme.surface, Theme.text, 0.16f));
        editor.setMarginLineEnabled(false);
        editor.setMatchedBracketBGColor(Theme.blend(Theme.surface, Theme.text, 0.14f));
        editor.setMatchedBracketBorderColor(null);
        editorScroll.getGutter().setBackground(Theme.surface);
        editorScroll.getGutter().setLineNumberColor(Theme.muted);
        editorScroll.getGutter().setBorderColor(Theme.border);

        SyntaxScheme scheme = editor.getSyntaxScheme();
        for (int i = 0; i < scheme.getStyleCount(); i++) {
            if (scheme.getStyle(i) != null) {
                scheme.getStyle(i).foreground = Theme.text;
                scheme.getStyle(i).background = null;
                scheme.getStyle(i).font = Theme.mono;
            }
        }
        for (int t : new int[] {Token.RESERVED_WORD, Token.RESERVED_WORD_2}) {
            if (scheme.getStyle(t) != null) scheme.getStyle(t).font = Theme.monoBold;
        }
        color(scheme, Theme.ins, Token.LITERAL_STRING_DOUBLE_QUOTE, Token.LITERAL_CHAR, Token.LITERAL_BACKQUOTE);
        color(scheme, Theme.upd, Token.LITERAL_NUMBER_DECIMAL_INT, Token.LITERAL_NUMBER_FLOAT,
                Token.LITERAL_NUMBER_HEXADECIMAL, Token.LITERAL_BOOLEAN);
        color(scheme, Theme.muted, Token.COMMENT_EOL, Token.COMMENT_MULTILINE, Token.COMMENT_DOCUMENTATION);
        color(scheme, Theme.blend(Theme.text, Theme.muted, 0.5f), Token.FUNCTION, Token.DATA_TYPE);
        editor.revalidate();
        editor.repaint();
    }

    private static void color(SyntaxScheme scheme, Color c, int... tokens) {
        for (int t : tokens) if (scheme.getStyle(t) != null) scheme.getStyle(t).foreground = c;
    }

    private void execute() {
        if (db == null || !run.isEnabled()) return;
        String selected = editor.getSelectedText();
        String script = selected != null && !selected.isBlank() ? selected : editor.getText();
        if (Sql.stripComments(script).isBlank()) return;

        run.setEnabled(false);
        stop.setEnabled(true);
        info.setForeground(Theme.muted);
        info.setText("running…");

        db.work(c -> {
            long t0 = System.nanoTime();
            StringBuilder log = new StringBuilder();
            String[] columns = null;
            List<Object[]> rows = new ArrayList<>();
            boolean truncated = false, failed = false;
            for (String raw : statements(script)) {
                String sql = raw.startsWith(".") ? dot(raw, log) : raw;
                if (sql == null) continue;
                long s0 = System.nanoTime();
                try (Statement st = c.createStatement()) {
                    current = st;
                    if (st.execute(sql)) {
                        try (ResultSet rs = st.getResultSet()) {
                            ResultSetMetaData md = rs.getMetaData();
                            int n = md.getColumnCount();
                            columns = new String[n];
                            for (int i = 0; i < n; i++) columns[i] = md.getColumnLabel(i + 1);
                            rows = new ArrayList<>();
                            truncated = false;
                            while (rs.next()) {
                                if (rows.size() >= MAX_ROWS) {
                                    truncated = true;
                                    break;
                                }
                                Object[] row = new Object[n];
                                for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                                rows.add(row);
                            }
                        }
                        log.append(oneLine(raw)).append("\n  → ").append(Format.count(rows.size()))
                                .append(truncated ? "+ rows (display limit)" : " rows");
                    } else {
                        log.append(oneLine(raw)).append("\n  → ").append(Math.max(0, st.getUpdateCount()))
                                .append(" rows changed");
                    }
                    log.append("  ·  ").append(ms(s0)).append(" ms\n");
                } catch (SQLException e) {
                    log.append(oneLine(raw)).append("\n  ✗ ").append(e.getMessage()).append('\n');
                    failed = true;
                    break; // later statements usually depend on the failed one
                } finally {
                    current = null;
                }
            }
            return new Outcome(columns, rows, truncated, log.toString(), failed, ms(t0));
        }, out -> {
            done();
            messages.setText(out.log());
            messages.setCaretPosition(0);
            if (out.columns() != null) {
                model.set(out.columns(), out.rows());
                sizeColumns();
            }
            info.setForeground(out.failed() ? Theme.del : Theme.muted);
            info.setText(out.failed() ? "failed — see Messages"
                    : (out.columns() != null ? Format.count(out.rows().size()) + (out.truncated() ? "+" : "")
                    + " rows  ·  " : "") + out.millis() + " ms");
            lower.setSelectedIndex(out.failed() || out.columns() == null ? 1 : 0);
            win.sqlRan();
        }, err -> {
            done();
            messages.setText(String.valueOf(err.getMessage()));
            info.setForeground(Theme.del);
            info.setText("failed — see Messages");
            lower.setSelectedIndex(1);
        });
    }

    private void done() {
        run.setEnabled(true);
        stop.setEnabled(false);
    }

    private static long ms(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000;
    }

    private static String oneLine(String sql) {
        String s = sql.replaceAll("\\s+", " ").strip();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** Dot-commands sit on their own lines, between statements; everything else is SQL. */
    private static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            String pending = Sql.stripComments(sql.toString()).strip();
            if (line.strip().startsWith(".") && (pending.isEmpty() || pending.endsWith(";"))) {
                out.addAll(Sql.split(sql.toString()));
                sql.setLength(0);
                out.add(line.strip());
            } else {
                sql.append(line).append('\n');
            }
        }
        out.addAll(Sql.split(sql.toString()));
        return out;
    }

    private static String dot(String line, StringBuilder log) {
        String[] parts = line.strip().split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1].strip().replaceAll(";$", "") : null;
        String onTable = arg == null || arg.isEmpty() ? "" : " AND tbl_name = " + Sql.literal(arg) + " COLLATE NOCASE";
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case ".tables":
                return "SELECT name, type FROM sqlite_schema WHERE type IN ('table', 'view') "
                        + "AND name NOT LIKE 'sqlite_%' ORDER BY name";
            case ".schema":
                return "SELECT sql FROM sqlite_schema WHERE sql IS NOT NULL" + onTable + " ORDER BY tbl_name, type DESC, name";
            case ".indexes", ".indices":
                return "SELECT name, tbl_name AS \"table\" FROM sqlite_schema WHERE type = 'index'" + onTable + " ORDER BY name";
            case ".databases":
                return "PRAGMA database_list";
            case ".help":
                log.append(HELP).append('\n');
                return null;
            default:
                log.append(line).append("\n  ✗ unsupported dot-command\n").append(HELP).append('\n');
                return null;
        }
    }

    private void sizeColumns() {
        FontMetrics fm = result.getFontMetrics(Theme.mono);
        int ch = fm.charWidth('0');
        for (int i = 0; i < result.getColumnCount(); i++) {
            int chars = model.getColumnName(i).length() + 2;
            for (int r = 0; r < Math.min(model.getRowCount(), 100); r++) {
                chars = Math.max(chars, Format.cell(model.getValueAt(r, i), 60).length());
            }
            result.getColumnModel().getColumn(i).setPreferredWidth(Math.max(56, Math.min(440, chars * ch + 22)));
        }
    }

    private static final class ResultModel extends AbstractTableModel {
        private String[] columns = new String[0];
        private List<Object[]> rows = List.of();

        void set(String[] columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }
    }
}
