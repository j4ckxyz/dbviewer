package dev.dbviewer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Live log of row changes, newest first. Selecting an entry shows before/after values;
 * selected entries can be reverted.
 */
final class ChangesPanel extends JPanel {

    private static final int MAX = 5000;

    private final MainWindow win;
    private Db db;
    private final List<Change> items = new ArrayList<>();
    private final LogModel logModel = new LogModel();
    private final JTable log = new JTable(logModel);
    private final DetailModel detailModel = new DetailModel();
    private final JTable detail = new JTable(detailModel);
    private final JButton revert = new JButton("Revert");
    private final JButton clear = new JButton("Clear");
    private final IntConsumer onCount;

    ChangesPanel(MainWindow win, IntConsumer onCount) {
        super(new BorderLayout());
        this.win = win;
        this.onCount = onCount;

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        revert.setFocusable(false);
        revert.setToolTipText("Undo the selected changes in the database, newest first");
        revert.addActionListener(e -> revertSelected());
        clear.setFocusable(false);
        clear.setToolTipText("Empty this list (does not touch the database)");
        clear.addActionListener(e -> clear());
        bar.add(revert);
        bar.add(clear);
        log.setToolTipText("Double-click a change to show its row");
        add(bar, BorderLayout.NORTH);

        Theme.dense(log);
        log.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        int[] widths = {78, 58, 110, 70, 700};
        for (int i = 0; i < widths.length; i++) {
            log.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            if (i < 2) log.getColumnModel().getColumn(i).setMinWidth(widths[i]);
        }
        log.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, false, r, c);
                Change ch = items.get(r);
                setFont(c == 1 ? Theme.monoBold : Theme.mono);
                if (!sel) setForeground(c == 1 ? Theme.op(ch.op()) : c == 0 || ch.op() == Change.Op.INFO ? Theme.muted : Theme.text);
                return this;
            }
        });
        log.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selectionChanged();
        });
        log.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = log.rowAtPoint(e.getPoint());
                if (e.getClickCount() != 2 || r < 0) return;
                Change ch = items.get(r);
                if (!ch.table().isEmpty()) win.openTable(ch.table(), ch.key() == null ? null : ch.whereLiteral());
            }
        });

        Theme.dense(detail);
        detail.setDefaultRenderer(Object.class, new ValueRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, f, r, c);
                if (c == 0) setText(String.valueOf(v));
                if (!sel) {
                    boolean hit = detailModel.changed.get(r);
                    if (c == 0) setForeground(hit ? Theme.text : Theme.muted);
                    if (hit && c == 1 && detailModel.change.before() != null) setForeground(Theme.del);
                    if (hit && c == 2 && detailModel.change.after() != null) setForeground(Theme.ins);
                }
                return this;
            }
        });

        JScrollPane left = new JScrollPane(log);
        JScrollPane right = new JScrollPane(detail);
        left.setBorder(Theme.line(1, 0, 0, 0));
        right.setBorder(Theme.line(1, 1, 0, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.72);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        selectionChanged();
    }

    void setDb(Db db) {
        this.db = db;
        clear();
    }

    void add(List<Change> changes) {
        if (changes.isEmpty()) return;
        for (Change ch : changes) items.add(0, ch);
        logModel.fireTableRowsInserted(0, changes.size() - 1);
        if (items.size() > MAX) {
            int from = MAX, to = items.size() - 1;
            items.subList(from, items.size()).clear();
            logModel.fireTableRowsDeleted(from, to);
        }
        onCount.accept(items.size());
    }

    private void clear() {
        items.clear();
        logModel.fireTableDataChanged();
        onCount.accept(0);
    }

    private void selectionChanged() {
        int[] rows = log.getSelectedRows();
        boolean any = false;
        for (int r : rows) any |= items.get(r).revertable();
        revert.setEnabled(any && db != null);
        detailModel.set(rows.length == 1 ? items.get(rows[0]) : null);
    }

    private void revertSelected() {
        List<Change> picked = new ArrayList<>();
        for (int r : log.getSelectedRows()) if (items.get(r).revertable()) picked.add(items.get(r));
        if (picked.isEmpty() || db == null) return;
        picked.sort(Comparator.comparingLong(Change::seq).reversed());
        Schema schema = win.schema();
        db.work(c -> Db.revertAll(c, schema, picked), n -> {
            win.status("Reverted " + n + " of " + picked.size() + (picked.size() == 1 ? " change" : " changes")
                    + (n < picked.size() ? " — the rest no longer apply (row already gone or already back)" : ""));
            win.sqlRan();
        }, err -> win.status("Revert failed, nothing changed: " + err.getMessage()));
    }

    private final class LogModel extends AbstractTableModel {
        private final String[] names = {"Time", "Op", "Table", "Key", "Change"};

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Change ch = items.get(r);
            return switch (c) {
                case 0 -> Format.clock(ch.time()).substring(0, 8);
                case 1 -> ch.op() == Change.Op.INFO ? "info" : ch.op().name().toLowerCase();
                case 2 -> ch.table();
                case 3 -> ch.keyText();
                default -> ch.summary();
            };
        }
    }

    private static final class DetailModel extends AbstractTableModel {
        private final String[] names = {"Column", "Before", "After"};
        Change change;
        BitSet changed = new BitSet();

        void set(Change ch) {
            change = ch != null && ch.op() != Change.Op.INFO ? ch : null;
            changed = new BitSet();
            if (change != null) {
                if (change.op() == Change.Op.UPDATE) changed = change.changed();
                else changed.set(0, change.cols().size());
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return change == null ? 0 : change.cols().size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (c == 0) return change.cols().get(r);
            Object[] side = c == 1 ? change.before() : change.after();
            return side == null ? "" : side[r];
        }
    }
}
