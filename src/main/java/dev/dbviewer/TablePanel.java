package dev.dbviewer;

import dev.dbviewer.Schema.ColumnInfo;
import dev.dbviewer.Schema.ForeignKey;
import dev.dbviewer.Schema.IndexInfo;
import dev.dbviewer.Schema.TableInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One table: a lazily paged, live-updating data grid plus a structure view. Pages are fetched
 * as they scroll into view; a refresh swaps the visible pages in one go so nothing flickers.
 */
final class TablePanel extends JPanel {

    private static final Object LOADING = new Object();
    private static final int PAGE = 200;
    private static final int FLASH_MS = 2500;

    private record Flash(long time, Change.Op op, BitSet cols) {}

    private record Loaded(long count, Map<Integer, Object[][]> pages) {}

    private final MainWindow win;
    private final Db db;
    private TableInfo info;
    private List<ColumnInfo> cols;
    private int off;

    private final Model model = new Model();
    private final JTable table;
    private final JTextField filter = new JTextField();
    private final JLabel countLabel = new JLabel();
    private final JLabel error = new JLabel();
    private final JButton addRow = new JButton("+ Row");
    private final JButton deleteRows = new JButton("Delete");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JPanel structure = new JPanel(new BorderLayout());
    private boolean structureBuilt;

    private String where = "";
    private int sortCol = -1;
    private boolean sortAsc = true;
    private int queryGen;
    private boolean busy, again, stale = true, widthsSet;
    private final Map<List<Object>, Flash> flashes = new HashMap<>();
    private final Timer flashTimer;

    TablePanel(MainWindow win, Db db, TableInfo info) {
        super(new BorderLayout());
        this.win = win;
        this.db = db;
        this.info = info;
        this.cols = info.columns();
        this.off = info.hasRowid() ? 1 : 0;

        table = new JTable(model) {
            @Override
            public Component prepareEditor(TableCellEditor editor, int row, int column) {
                Component c = super.prepareEditor(editor, row, column);
                c.setFont(Theme.mono);
                return c;
            }
        };
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        table.setFont(Theme.mono);
        table.setRowHeight(Theme.ROW_HEIGHT);
        table.setDefaultRenderer(Object.class, new Cell());
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", true);
        installHeader();
        installMouse();
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());

        add(buildBar(), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(Theme.line(1, 0, 0, 0));
        body.add(scroll, "data");
        body.add(structure, "structure");
        add(body, BorderLayout.CENTER);

        flashTimer = new Timer(50, e -> {
            long now = System.currentTimeMillis();
            flashes.values().removeIf(f -> now - f.time() > FLASH_MS);
            if (flashes.isEmpty()) ((Timer) e.getSource()).stop();
            table.repaint();
        });
        updateButtons();
    }

    String tableName() {
        return info.name();
    }

    // ---- toolbar --------------------------------------------------------------------------

    private JPanel buildBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

        JToggleButton data = new JToggleButton("Data", true);
        JToggleButton struct = new JToggleButton("Structure");
        ButtonGroup group = new ButtonGroup();
        for (JToggleButton b : List.of(data, struct)) {
            b.setFocusable(false);
            group.add(b);
            bar.add(b);
        }
        data.addActionListener(e -> cards.show(body, "data"));
        struct.addActionListener(e -> {
            if (!structureBuilt) buildStructure();
            cards.show(body, "structure");
        });
        bar.add(Box.createHorizontalStrut(8));

        filter.setFont(Theme.mono);
        filter.putClientProperty("JTextField.placeholderText", "Filter rows, e.g.  status = 'open'");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.putClientProperty("JTextField.clearCallback", (Runnable) () -> setFilter(""));
        filter.addActionListener(e -> setFilter(filter.getText()));
        filter.registerKeyboardAction(e -> setFilter(""),
                javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_FOCUSED);
        bar.add(filter);
        bar.add(Box.createHorizontalStrut(8));

        countLabel.setFont(Theme.mono);
        countLabel.setForeground(Theme.muted);
        bar.add(countLabel);
        bar.add(Box.createHorizontalStrut(8));

        addRow.setFocusable(false);
        addRow.setToolTipText("Insert a row with default values");
        addRow.addActionListener(e -> insertRow());
        deleteRows.setFocusable(false);
        deleteRows.addActionListener(e -> deleteSelected());
        bar.add(addRow);
        bar.add(deleteRows);

        JPanel wrap = new JPanel(new BorderLayout());
        error.setBorder(BorderFactory.createEmptyBorder(2, 10, 4, 10));
        error.setFont(Theme.mono);
        error.setVisible(false);
        wrap.add(bar, BorderLayout.CENTER);
        wrap.add(error, BorderLayout.SOUTH);
        return wrap;
    }

    private void updateButtons() {
        addRow.setEnabled(info.isTable());
        deleteRows.setEnabled(info.keyed() && table.getSelectedRowCount() > 0);
    }

    private void showError(String message) {
        error.setVisible(message != null);
        error.setForeground(Theme.del);
        error.setText(message == null ? "" : message);
    }

    // ---- loading --------------------------------------------------------------------------

    /** Called when the tab becomes visible. */
    void shown() {
        if (stale) refresh();
        if (!flashes.isEmpty() && !flashTimer.isRunning()) flashTimer.start();
    }

    void setFilter(String text) {
        where = text == null ? "" : text.strip();
        if (!filter.getText().strip().equals(where)) filter.setText(where);
        queryGen++;
        table.scrollRectToVisible(new Rectangle(0, 0, 1, 1));
        refresh();
    }

    /** The table's definition changed (ALTER TABLE and friends). */
    void setInfo(TableInfo next) {
        if (next.equals(info)) {
            dataChanged();
            return;
        }
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        info = next;
        cols = next.columns();
        off = next.hasRowid() ? 1 : 0;
        sortCol = -1;
        widthsSet = false;
        structureBuilt = false;
        structure.removeAll();
        queryGen++;
        model.clear();
        model.fireTableStructureChanged();
        updateButtons();
        if (isShowing()) refresh();
        else stale = true;
    }

    /** Live changes for this table arrived from the monitor. */
    void onChanges(List<Change> changes) {
        long now = System.currentTimeMillis();
        for (Change ch : changes) {
            if (ch.key() == null || ch.op() == Change.Op.DELETE || ch.op() == Change.Op.INFO) continue;
            flashes.put(normalize(ch.key()), new Flash(now, ch.op(), ch.changed()));
        }
        dataChanged();
    }

    void dataChanged() {
        if (isShowing()) {
            refresh();
            if (!flashes.isEmpty() && !flashTimer.isRunning()) flashTimer.start();
        } else {
            stale = true;
        }
    }

    private String fromWhere() {
        return " FROM main." + Sql.q(info.name()) + (where.isEmpty() ? "" : " WHERE (" + where + ")");
    }

    private String selectSql() {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (off == 1) sb.append("rowid, ");
        sb.append(Sql.qList(info.columnNames())).append(fromWhere());
        if (sortCol >= 0 && sortCol < cols.size()) {
            sb.append(" ORDER BY ").append(Sql.q(cols.get(sortCol).name())).append(sortAsc ? "" : " DESC");
        }
        return sb.append(" LIMIT ").append(PAGE).append(" OFFSET ").toString();
    }

    private void refresh() {
        stale = false;
        if (busy) {
            again = true;
            return;
        }
        busy = true;
        final int gen = queryGen;
        Rectangle vis = table.getVisibleRect();
        int first = Math.max(0, vis.y / Theme.ROW_HEIGHT) / PAGE;
        int last = Math.max(0, (vis.y + Math.max(vis.height, 600)) / Theme.ROW_HEIGHT) / PAGE;
        String countSql = "SELECT COUNT(*)" + fromWhere();
        String pageSql = selectSql();

        db.read(c -> {
            long n = Sql.scalarLong(c, countSql);
            Map<Integer, Object[][]> got = new HashMap<>();
            for (int p = first; p <= last; p++) {
                if (p == 0 || (long) p * PAGE < n) got.put(p, Sql.rows(c, pageSql + (long) p * PAGE));
            }
            return new Loaded(n, got);
        }, res -> {
            busy = false;
            if (gen == queryGen) {
                model.swap(res);
                showError(null);
                countLabel.setText(Format.count(res.count()) + (where.isEmpty() ? " rows" : " rows (filtered)"));
                if (!widthsSet) sizeColumns();
            }
            if (again || gen != queryGen) {
                again = false;
                refresh();
            }
        }, err -> {
            busy = false;
            again = false;
            showError(err.getMessage());
        });
    }

    private void sizeColumns() {
        Object[][] sample = model.pages.get(0);
        if (sample == null) return;
        FontMetrics fm = table.getFontMetrics(Theme.mono);
        int ch = fm.charWidth('0');
        for (int i = 0; i < cols.size() && i < table.getColumnCount(); i++) {
            ColumnInfo c = cols.get(i);
            int chars = c.name().length() + c.type().length() + 4;
            ForeignKey fk = info.fkFor(c.name());
            if (fk != null) chars += fk.toTable().length() + 3;
            for (int r = 0; r < Math.min(sample.length, 100); r++) {
                chars = Math.max(chars, Format.cell(sample[r][i + off], 60).length());
            }
            table.getColumnModel().getColumn(i).setPreferredWidth(Math.max(56, Math.min(440, chars * ch + 22)));
        }
        widthsSet = sample.length > 0;
    }

    // ---- model ----------------------------------------------------------------------------

    private final class Model extends AbstractTableModel {
        int rows;
        int gen;
        final Map<Integer, Object[][]> pages = new HashMap<>();
        final Set<Integer> pending = new HashSet<>();

        void clear() {
            gen++;
            rows = 0;
            pages.clear();
            pending.clear();
        }

        void swap(Loaded l) {
            int old = rows;
            gen++;
            rows = (int) Math.min(l.count(), Integer.MAX_VALUE);
            pages.clear();
            pages.putAll(l.pages());
            pending.clear();
            if (rows > old) fireTableRowsInserted(old, rows - 1);
            else if (rows < old) fireTableRowsDeleted(rows, old - 1);
            if (rows > 0) fireTableRowsUpdated(0, rows - 1);
        }

        Object[] row(int r) {
            Object[][] p = pages.get(r / PAGE);
            if (p == null) {
                fetch(r / PAGE);
                return null;
            }
            int i = r % PAGE;
            return i < p.length ? p[i] : null;
        }

        private void fetch(int page) {
            if (!pending.add(page)) return;
            final int g = gen;
            String sql = selectSql() + (long) page * PAGE;
            db.read(c -> Sql.rows(c, sql), got -> {
                if (g != gen) return;
                pending.remove(page);
                pages.put(page, got);
                if (pages.size() > 40) {
                    int far = page;
                    for (int p : pages.keySet()) if (Math.abs(p - page) > Math.abs(far - page)) far = p;
                    pages.remove(far);
                }
                int from = page * PAGE;
                if (from < rows) fireTableRowsUpdated(from, Math.min(rows, from + PAGE) - 1);
            }, err -> showError(err.getMessage()));
        }

        @Override
        public int getRowCount() {
            return rows;
        }

        @Override
        public int getColumnCount() {
            return cols.size();
        }

        @Override
        public String getColumnName(int c) {
            return cols.get(c).name();
        }

        @Override
        public Object getValueAt(int r, int c) {
            Object[] row = row(r);
            return row == null ? LOADING : row[c + off];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            if (!info.keyed() || cols.get(c).generated()) return false;
            Object[] row = pages.containsKey(r / PAGE) ? row(r) : null;
            if (row == null) return false;
            Object v = row[c + off];
            return !(v instanceof byte[]) && !(v instanceof String s && (s.indexOf('\n') >= 0 || s.length() > 2000));
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            Object[] row = row(r);
            if (row == null || value == null) return;
            Object old = row[c + off];
            String text = value.toString();
            if (old == null ? text.isEmpty() : text.equals(old.toString())) return;
            writeCell(row, c, coerce(text, old, cols.get(c)));
        }
    }

    private static Object coerce(String text, Object old, ColumnInfo col) {
        String type = col.type().toUpperCase(Locale.ROOT);
        boolean numeric = old instanceof Number || (old == null && (type.contains("INT") || type.contains("REAL")
                || type.contains("NUM") || type.contains("DEC") || type.contains("FLOA") || type.contains("DOUB")));
        if (!numeric) return text;
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException e2) {
                return text;
            }
        }
    }

    // ---- writes ---------------------------------------------------------------------------

    private Object[] keyOf(Object[] row) {
        if (off == 1) return new Object[] {row[0]};
        List<String> pk = info.pk();
        Object[] key = new Object[pk.size()];
        for (int i = 0; i < key.length; i++) key[i] = row[info.columnIndex(pk.get(i)) + off];
        return key;
    }

    private String keyWhere() {
        if (off == 1) return "rowid = ?";
        List<String> parts = new ArrayList<>();
        for (String k : info.pk()) parts.add(Sql.q(k) + " = ?");
        return String.join(" AND ", parts);
    }

    private static List<Object> normalize(Object[] key) {
        List<Object> out = new ArrayList<>(key.length);
        for (Object k : key) {
            out.add(k instanceof Number n && !(k instanceof Double) && !(k instanceof Float) ? (Object) n.longValue() : k);
        }
        return out;
    }

    private void writeCell(Object[] row, int col, Object value) {
        Object[] key = keyOf(row);
        Object[] params = new Object[key.length + 1];
        params[0] = value;
        System.arraycopy(key, 0, params, 1, key.length);
        String sql = "UPDATE main." + Sql.q(info.name()) + " SET " + Sql.q(cols.get(col).name()) + " = ? WHERE "
                + keyWhere();
        db.work(c -> Sql.update(c, sql, params), n -> {
            showError(null);
            refresh();
        }, err -> showError(err.getMessage()));
    }

    private void insertRow() {
        String sql = "INSERT INTO main." + Sql.q(info.name()) + " DEFAULT VALUES";
        db.work(c -> Sql.update(c, sql), n -> {
            showError(null);
            win.status("Inserted 1 row into " + info.name());
            refresh();
        }, err -> showError(err.getMessage() + "   — columns without defaults need an INSERT from the SQL tab"));
    }

    private void deleteSelected() {
        List<Object[]> keys = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            Object[] row = model.pages.containsKey(r / PAGE) ? model.row(r) : null;
            if (row != null) keys.add(keyOf(row));
        }
        if (keys.isEmpty()) return;
        boolean undoable = win.isTracked(info.name());
        if (keys.size() > 1 || !undoable) {
            String msg = "Delete " + keys.size() + (keys.size() == 1 ? " row" : " rows") + " from " + info.name() + "?"
                    + (undoable ? "\nEach delete can be reverted from the Changes panel."
                    : "\nThis table is not change-tracked, so the delete cannot be reverted from the Changes panel.");
            if (JOptionPane.showConfirmDialog(this, msg, "Delete rows", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        }
        String sql = "DELETE FROM main." + Sql.q(info.name()) + " WHERE " + keyWhere();
        db.work(c -> {
            Sql.exec(c, "SAVEPOINT dbv_delete");
            try {
                int n = 0;
                for (Object[] k : keys) n += Sql.update(c, sql, k);
                Sql.exec(c, "RELEASE dbv_delete");
                return n;
            } catch (Exception e) {
                Sql.exec(c, "ROLLBACK TO dbv_delete");
                Sql.exec(c, "RELEASE dbv_delete");
                throw e;
            }
        }, n -> {
            showError(null);
            table.clearSelection();
            win.status("Deleted " + n + (n == 1 ? " row" : " rows") + " from " + info.name());
            refresh();
        }, err -> showError(err.getMessage()));
    }

    private void editInDialog(Object[] row, int col) {
        Object old = row[col + off];
        JTextArea area = new JTextArea(old == null ? "" : old.toString(), 14, 60);
        area.setFont(Theme.mono);
        area.setLineWrap(true);
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(area), info.name() + "." + cols.get(col).name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String text = area.getText();
        if (old != null && text.equals(old.toString())) return;
        writeCell(row, col, coerce(text, old, cols.get(col)));
    }

    // ---- header, mouse, navigation ----------------------------------------------------------

    private void installHeader() {
        TableCellRenderer base = table.getTableHeader().getDefaultRenderer();
        table.getTableHeader().setDefaultRenderer((t, value, sel, focus, row, column) -> {
            Component comp = base.getTableCellRendererComponent(t, value, sel, focus, row, column);
            int m = t.convertColumnIndexToModel(column);
            if (comp instanceof JLabel l && m < cols.size()) {
                ColumnInfo c = cols.get(m);
                ForeignKey fk = info.fkFor(c.name());
                StringBuilder sb = new StringBuilder("<html><nobr>");
                                sb.append(c.pk() > 0 ? "<b>" : "").append(esc(c.name())).append(c.pk() > 0 ? "</b>" : "");
                sb.append(" <font color='").append(Theme.hex(Theme.muted)).append("'>")
                        .append(esc(c.type().toLowerCase(Locale.ROOT))).append("</font>");
                if (fk != null) {
                    sb.append(" <font color='").append(Theme.hex(Theme.muted)).append("'>→ ")
                            .append(esc(fk.toTable())).append("</font>");
                }
                if (m == sortCol) sb.append(sortAsc ? " ▲" : " ▼");
                l.setText(sb.append("</nobr></html>").toString());
                l.setFont(Theme.mono);
                l.setHorizontalAlignment(SwingConstants.LEFT);
                l.setToolTipText(c.name() + "  " + c.type() + (c.notNull() ? "  NOT NULL" : "")
                        + (c.dflt() != null ? "  DEFAULT " + c.dflt() : "")
                        + (fk != null ? "  → " + fk.toTable() + "(" + String.join(", ", fk.to()) + ")" : ""));
            }
            return comp;
        });
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int v = table.columnAtPoint(e.getPoint());
                if (v < 0 || e.getButton() != MouseEvent.BUTTON1) return;
                int m = table.convertColumnIndexToModel(v);
                if (sortCol != m) {
                    sortCol = m;
                    sortAsc = true;
                } else if (sortAsc) {
                    sortAsc = false;
                } else {
                    sortCol = -1;
                }
                queryGen++;
                table.getTableHeader().repaint();
                refresh();
            }
        });
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void installMouse() {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                boolean mod = (e.getModifiersEx() & Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) != 0;
                if (!mod || e.getButton() != MouseEvent.BUTTON1) return;
                Point p = e.getPoint();
                int r = table.rowAtPoint(p), c = table.columnAtPoint(p);
                if (r < 0 || c < 0) return;
                Object[] row = model.pages.containsKey(r / PAGE) ? model.row(r) : null;
                ForeignKey fk = info.fkFor(cols.get(c).name());
                if (row != null && fk != null && row[c + off] != null) follow(fk, row);
            }
        });
    }

    private void follow(ForeignKey fk, Object[] row) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < fk.from().size() && i < fk.to().size(); i++) {
            parts.add(Sql.equalsExpr(fk.to().get(i), row[info.columnIndex(fk.from().get(i)) + off]));
        }
        win.openTable(fk.toTable(), String.join(" AND ", parts));
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
        if (r < 0 || c < 0) return;
        if (!table.isRowSelected(r)) table.setRowSelectionInterval(r, r);
        Object[] row = model.pages.containsKey(r / PAGE) ? model.row(r) : null;
        if (row == null) return;
        Object value = row[c + off];
        ColumnInfo col = cols.get(c);
        JPopupMenu menu = new JPopupMenu();

        menu.add(item("Copy value", () -> copy(value == null ? "" : value instanceof byte[] b ? Sql.literal(b)
                : value.toString())));
        menu.add(item("Copy row", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append('\t');
                Object v = row[i + off];
                sb.append(v == null ? "" : v instanceof byte[] b ? Sql.literal(b) : v.toString());
            }
            copy(sb.toString());
        }));

        ForeignKey fk = info.fkFor(col.name());
        List<ForeignKey> inbound = new ArrayList<>();
        for (ForeignKey in : win.schema().inbound(info.name())) {
            if (in.to().stream().anyMatch(t -> t.equalsIgnoreCase(col.name()))) inbound.add(in);
        }
        if ((fk != null && value != null) || !inbound.isEmpty()) menu.addSeparator();
        if (fk != null && value != null) {
            menu.add(item("Go to " + fk.toTable() + " row  (" + String.join(", ", fk.to()) + " = "
                    + Format.cell(value, 30) + ")", () -> follow(fk, row)));
        }
        for (ForeignKey in : inbound) {
            menu.add(item("Show " + in.fromTable() + " rows referencing this  (" + String.join(", ", in.from())
                    + ")", () -> {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < in.from().size() && i < in.to().size(); i++) {
                    parts.add(Sql.equalsExpr(in.from().get(i), row[info.columnIndex(in.to().get(i)) + off]));
                }
                win.openTable(in.fromTable(), String.join(" AND ", parts));
            }));
        }

        if (info.keyed()) {
            menu.addSeparator();
            if (!col.generated() && !(value instanceof byte[])) {
                menu.add(item("Edit value…", () -> editInDialog(row, c)));
                if (value != null) menu.add(item("Set NULL", () -> writeCell(row, c, null)));
            }
            int n = table.getSelectedRowCount();
            menu.add(item(n > 1 ? "Delete " + n + " rows" : "Delete row", this::deleteSelected));
        }
        menu.show(table, e.getX(), e.getY());
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> action.run());
        return i;
    }

    private static void copy(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    // ---- cells ----------------------------------------------------------------------------

    private final class Cell extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            setBackground(null); // a flash colour set on the last cell must not stick to this one
            super.getTableCellRendererComponent(t, "", sel, false, r, c);
            setFont(Theme.mono);
            int m = t.convertColumnIndexToModel(c);
            Color fore = sel ? t.getSelectionForeground() : Theme.text;
            if (v == LOADING) {
                setText("·");
                fore = Theme.muted;
            } else if (v == null) {
                setText("NULL");
                setFont(Theme.mono.deriveFont(Font.ITALIC));
                if (!sel) fore = Theme.muted;
            } else {
                setText(Format.cell(v, 300));
                if (!sel && v instanceof byte[]) fore = Theme.muted;
                else if (m < cols.size() && info.fkFor(cols.get(m).name()) != null) setFont(Theme.link);
            }
            setForeground(fore);

            if (!sel && !flashes.isEmpty() && model.pages.containsKey(r / PAGE)) {
                Object[] row = model.row(r);
                Flash f = row == null || !info.keyed() ? null : flashes.get(normalize(keyOf(row)));
                if (f != null && (f.op() == Change.Op.INSERT || f.cols().get(m))) {
                    float age = (System.currentTimeMillis() - f.time()) / (float) FLASH_MS;
                    Color hl = f.op() == Change.Op.INSERT ? Theme.ins : Theme.upd;
                    setBackground(Theme.blend(getBackground(), hl, 0.42f * (1 - age)));
                }
            }
            return this;
        }
    }

    // ---- structure ------------------------------------------------------------------------

    private void buildStructure() {
        structureBuilt = true;
        DefaultTableModel m = new DefaultTableModel(
                new String[] {"#", "Column", "Type", "Key", "Not null", "Default", "References"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (ColumnInfo c : cols) {
            ForeignKey fk = info.fkFor(c.name());
            m.addRow(new Object[] {c.cid(), c.name(), c.type(), c.pk() > 0 ? "PK" + (info.pk().size() > 1 ? " " + c.pk() : "") : "",
                    c.notNull() ? "yes" : "", c.dflt() == null ? "" : c.dflt(),
                    fk == null ? "" : fk.toTable() + "(" + String.join(", ", fk.to()) + ")  on delete "
                            + fk.onDelete().toLowerCase(Locale.ROOT)});
        }
        JTable t = new JTable(m);
        Theme.dense(t);
        int[] widths = {40, 200, 140, 60, 70, 160, 320};
        for (int i = 0; i < widths.length; i++) t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        StringBuilder ddl = new StringBuilder();
        ddl.append(info.sql() == null ? "-- no stored definition" : info.sql()).append(";\n");
        for (IndexInfo ix : info.indexes()) {
            ddl.append('\n');
            if (ix.sql() != null) ddl.append(ix.sql()).append(";\n");
            else ddl.append("-- automatic ").append(ix.unique() ? "unique " : "").append("index ").append(ix.name())
                    .append(" (").append(String.join(", ", ix.columns())).append(")\n");
        }
        for (String trg : info.triggers()) ddl.append('\n').append(trg).append(";\n");
        JTextArea area = new JTextArea(ddl.toString());
        area.setFont(Theme.mono);
        area.setEditable(false);
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        area.setCaretPosition(0);

        JScrollPane top = new JScrollPane(t);
        JScrollPane bottom = new JScrollPane(area);
        top.setBorder(Theme.line(1, 0, 0, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        top.setPreferredSize(new Dimension(100, 240));
        structure.add(split);
        structure.revalidate();
    }
}
