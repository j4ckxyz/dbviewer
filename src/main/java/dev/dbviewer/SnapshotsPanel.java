package dev.dbviewer;

import dev.dbviewer.Snapshots.Snapshot;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/** Named whole-database snapshots: take one, restore one, delete one. */
final class SnapshotsPanel extends JPanel {

    private final MainWindow win;
    private Db db;
    private List<Snapshot> items = new ArrayList<>();
    private final ListModel model = new ListModel();
    private final JTable table = new JTable(model);
    private final JButton take = new JButton("New snapshot");
    private final JButton restore = new JButton("Restore…");
    private final JButton delete = new JButton("Delete");

    SnapshotsPanel(MainWindow win) {
        super(new BorderLayout());
        this.win = win;

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        for (JButton b : List.of(take, restore, delete)) {
            b.setFocusable(false);
            bar.add(b);
        }
        take.addActionListener(e -> takeSnapshot());
        restore.addActionListener(e -> restoreSelected());
        delete.addActionListener(e -> deleteSelected());
        add(bar, BorderLayout.NORTH);

        Theme.dense(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, false, r, c);
                setFont(Theme.mono);
                if (!sel) setForeground(c == 0 ? Theme.text : Theme.muted);
                return this;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(Theme.line(1, 0, 0, 0));
        add(scroll, BorderLayout.CENTER);
        updateButtons();
    }

    void setDb(Db db) {
        this.db = db;
        reload();
    }

    void takeSnapshot() {
        if (db == null) return;
        String name = (String) JOptionPane.showInputDialog(this, "Name for this snapshot:", "Take snapshot",
                JOptionPane.PLAIN_MESSAGE, null, null, "snapshot");
        if (name == null) return;
        Db target = db;
        target.work(c -> Snapshots.take(c, target.path, name), snap -> {
            win.status("Snapshot “" + snap.name() + "” saved (" + Format.bytes(snap.size()) + ")");
            reload();
        }, err -> win.status("Snapshot failed: " + err.getMessage()));
    }

    private void restoreSelected() {
        int r = table.getSelectedRow();
        if (r < 0 || db == null) return;
        Snapshot snap = items.get(r);
        int choice = JOptionPane.showConfirmDialog(this,
                "Restore “" + snap.name() + "” from " + Format.stamp(snap.time()) + "?\n\n"
                        + "This replaces everything in " + db.path.getFileName() + " — schema and data — with the snapshot.\n"
                        + "A snapshot of the current state is taken first, so you can come back.",
                "Restore snapshot", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        Db target = db;
        target.work(c -> {
            Snapshots.take(c, target.path, "before restoring " + snap.name());
            Snapshots.restore(c, snap);
            return snap;
        }, s -> {
            win.status("Restored “" + s.name() + "”. Previous state saved as a snapshot.");
            reload();
            win.sqlRan();
        }, err -> {
            win.status("Restore failed: " + err.getMessage());
            reload();
        });
    }

    private void deleteSelected() {
        int r = table.getSelectedRow();
        if (r < 0) return;
        Snapshot snap = items.get(r);
        if (JOptionPane.showConfirmDialog(this, "Delete snapshot “" + snap.name() + "”? This cannot be undone.",
                "Delete snapshot", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Snapshots.delete(snap);
        } catch (Exception e) {
            win.status("Could not delete snapshot: " + e.getMessage());
        }
        reload();
    }

    private void reload() {
        try {
            items = db == null ? new ArrayList<>() : Snapshots.list(db.path);
        } catch (Exception e) {
            items = new ArrayList<>();
            win.status("Could not read snapshots: " + e.getMessage());
        }
        model.fireTableDataChanged();
        updateButtons();
    }

    private void updateButtons() {
        boolean sel = table.getSelectedRow() >= 0;
        take.setEnabled(db != null);
        restore.setEnabled(sel && db != null);
        delete.setEnabled(sel);
    }

    private final class ListModel extends AbstractTableModel {
        private final String[] names = {"Name", "Taken", "Size"};

        @Override
        public int getRowCount() {
            return items.size();
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
            Snapshot s = items.get(r);
            return switch (c) {
                case 0 -> s.name();
                case 1 -> Format.stamp(s.time());
                default -> Format.bytes(s.size());
            };
        }
    }
}
