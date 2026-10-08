package dev.dbviewer;

import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Font;

/** Monospace cell renderer for raw SQLite values: muted NULL and BLOB. */
class ValueRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
        super.getTableCellRendererComponent(t, "", sel, false, r, c);
        setFont(v == null ? Theme.mono.deriveFont(Font.ITALIC) : Theme.mono);
        setText(Format.cell(v, 300));
        setForeground(sel ? t.getSelectionForeground() : v == null || v instanceof byte[] ? Theme.muted : Theme.text);
        return this;
    }
}
