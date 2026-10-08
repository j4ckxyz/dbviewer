package dev.dbviewer;

import dev.dbviewer.Schema.TableInfo;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Table list with live row counts. A dot lights up next to a table when its data changes. */
final class Sidebar extends JPanel {

    private static final int FLASH_MS = 1600;

    private final DefaultListModel<TableInfo> model = new DefaultListModel<>();
    private final JList<TableInfo> list = new JList<>(model);
    private final JTextField filter = new JTextField();
    private Schema schema;
    private Map<String, Long> counts = Map.of();
    private Set<String> untracked = Set.of();
    private final Map<String, Long> flashes = new HashMap<>();
    private final Timer flashTimer;

    Sidebar(Consumer<String> onOpen) {
        super(new BorderLayout());
        filter.putClientProperty("JTextField.placeholderText", "Filter tables");
        filter.putClientProperty("JTextField.showClearButton", true);
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        top.add(filter);
        add(top, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(Theme.ROW_HEIGHT);
        list.setCellRenderer(new Row());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) onOpen.accept(model.get(i).name());
            }
        });
        list.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER && list.getSelectedValue() != null) {
                    onOpen.accept(list.getSelectedValue().name());
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(230, 100));
        setMinimumSize(new Dimension(120, 100));

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuild();
            }
        });

        flashTimer = new Timer(60, e -> {
            long now = System.currentTimeMillis();
            flashes.values().removeIf(t -> now - t > FLASH_MS);
            if (flashes.isEmpty()) ((Timer) e.getSource()).stop();
            list.repaint();
        });
    }

    void setSchema(Schema schema) {
        this.schema = schema;
        rebuild();
    }

    void setCounts(Map<String, Long> counts, Set<String> untracked) {
        this.counts = counts;
        this.untracked = untracked;
        list.repaint();
    }

    void flash(String table) {
        flashes.put(table, System.currentTimeMillis());
        if (!flashTimer.isRunning()) flashTimer.start();
    }

    void select(String table) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).name().equalsIgnoreCase(table)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
        list.clearSelection();
    }

    private void rebuild() {
        TableInfo selected = list.getSelectedValue();
        model.clear();
        if (schema == null) return;
        String needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        // tables first, then views and virtual tables
        List<TableInfo> sorted = schema.tables.stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted((a, b) -> Boolean.compare(!a.isTable(), !b.isTable()))
                .toList();
        for (TableInfo t : sorted) model.addElement(t);
        if (selected != null) select(selected.name());
    }

    private final class Row extends JComponent implements ListCellRenderer<TableInfo> {
        private TableInfo table;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends TableInfo> l, TableInfo value, int index,
                                                      boolean isSelected, boolean focus) {
            table = value;
            selected = isSelected;
            setToolTipText(untracked.contains(value.name())
                    ? value.name() + " — too large for row-level change tracking; row count is still watched"
                    : null);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();
            Color back = selected ? list.getSelectionBackground() : list.getBackground();
            Color fore = selected ? list.getSelectionForeground() : Theme.text;
            g.setColor(back);
            g.fillRect(0, 0, w, h);

            Long flashed = flashes.get(table.name());
            if (flashed != null) {
                float age = (System.currentTimeMillis() - flashed) / (float) FLASH_MS;
                g.setColor(Theme.blend(back, Theme.upd, 1 - age));
                g.fillOval(7, h / 2 - 3, 6, 6);
            }

            String tag = !table.isTable() ? table.kind()
                    : counts.containsKey(table.name()) ? Format.count(counts.get(table.name())) : "";
            if (untracked.contains(table.name())) tag += " ◌";
            g.setFont(Theme.small);
            FontMetrics small = g.getFontMetrics();
            int tagWidth = small.stringWidth(tag);
            g.setColor(selected ? fore : Theme.muted);
            g.drawString(tag, w - 10 - tagWidth, (h + small.getAscent() - small.getDescent()) / 2);

            g.setFont(Theme.mono);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(fore);
            String name = table.name();
            int room = w - 20 - tagWidth - 18;
            if (fm.stringWidth(name) > room) {
                int n = name.length();
                while (n > 1 && fm.stringWidth(name.substring(0, n) + "…") > room) n--;
                name = name.substring(0, n) + "…";
            }
            g.drawString(name, 20, (h + fm.getAscent() - fm.getDescent()) / 2);
        }
    }
}
