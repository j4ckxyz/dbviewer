package dev.dbviewer;

import dev.dbviewer.Schema.ColumnInfo;
import dev.dbviewer.Schema.ForeignKey;
import dev.dbviewer.Schema.TableInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Relationship diagram: every table as a card, every foreign key as a curve from the
 * referencing column to the referenced one. Referenced tables sit to the left of the tables
 * that point at them.
 */
final class GraphPanel extends JPanel {

    private static final int HEADER = 28;
    private static final int ROW = 20;
    private static final int COLUMN_GAP = 110;
    private static final int ROW_GAP = 26;
    private static final int MAX_COLUMN_HEIGHT = 1100;
    private static final int FLASH_MS = 1600;

    private static final class Node {
        TableInfo table;
        double x, y;
        int w, h;

        double cx() {
            return x + w / 2.0;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private record Edge(Node from, int fromRow, Node to, int toRow, ForeignKey fk) {}

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private Map<String, Long> counts = Map.of();
    private final Map<String, Long> flashes = new HashMap<>();
    private final Consumer<String> onOpen;

    private double scale = 1, tx = 40, ty = 40;
    private boolean needFit = true;
    private Node hover, selected, dragging;
    private Point last;
    private final Canvas canvas = new Canvas();
    private final Timer flashTimer;

    GraphPanel(Consumer<String> onOpen) {
        super(new BorderLayout());
        this.onOpen = onOpen;

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        bar.add(button("Fit", this::fit));
        bar.add(button("Tidy", () -> {
            layoutNodes(new ArrayList<>(nodes.values()), 0);
            fit();
        }));
        bar.setToolTipText("Drag cards to move. Scroll to pan, Ctrl/⌘ + scroll to zoom. Double-click a table to open it.");
        add(bar, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);

        flashTimer = new Timer(50, e -> {
            long now = System.currentTimeMillis();
            flashes.values().removeIf(t -> now - t > FLASH_MS);
            if (flashes.isEmpty()) ((Timer) e.getSource()).stop();
            canvas.repaint();
        });
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    void setSchema(Schema schema) {
        Map<String, Node> old = new HashMap<>(nodes);
        nodes.clear();
        edges.clear();
        FontMetrics name = getFontMetrics(Theme.monoBold);
        FontMetrics body = getFontMetrics(Theme.mono);

        List<Node> fresh = new ArrayList<>();
        for (TableInfo t : schema.tables) {
            Node n = new Node();
            n.table = t;
            int w = name.stringWidth(t.name()) + 90;
            for (ColumnInfo c : t.columns()) {
                w = Math.max(w, 22 + body.stringWidth(c.name()) + 18 + body.stringWidth(c.type()) + 10);
            }
            n.w = Math.max(170, Math.min(340, w));
            n.h = HEADER + t.columns().size() * ROW + 6;
            Node prev = old.get(t.name());
            if (prev != null) {
                n.x = prev.x;
                n.y = prev.y;
            } else {
                fresh.add(n);
            }
            nodes.put(t.name(), n);
        }

        Map<String, Node> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byName.putAll(nodes);
        for (Node n : nodes.values()) {
            for (ForeignKey fk : n.table.fks()) {
                Node parent = byName.get(fk.toTable());
                if (parent == null) continue;
                for (int i = 0; i < fk.from().size(); i++) {
                    int fromRow = n.table.columnIndex(fk.from().get(i));
                    int toRow = i < fk.to().size() ? parent.table.columnIndex(fk.to().get(i)) : -1;
                    if (fromRow >= 0) edges.add(new Edge(n, fromRow, parent, toRow, fk));
                }
            }
        }

        if (fresh.size() == nodes.size()) {
            layoutNodes(fresh, 0);
            needFit = true;
        } else if (!fresh.isEmpty()) {
            double bottom = 0;
            for (Node n : nodes.values()) if (!fresh.contains(n)) bottom = Math.max(bottom, n.y + n.h);
            layoutNodes(fresh, bottom + ROW_GAP * 2);
        }
        if (selected != null) selected = nodes.get(selected.table.name());
        hover = null;
        canvas.repaint();
    }

    void setCounts(Map<String, Long> counts) {
        this.counts = counts;
        canvas.repaint();
    }

    void flash(String table) {
        flashes.put(table, System.currentTimeMillis());
        if (isShowing() && !flashTimer.isRunning()) flashTimer.start();
    }

    // ---- layout ---------------------------------------------------------------------------

    /** Layered layout: a table goes one column right of the furthest table it references. */
    private void layoutNodes(List<Node> subject, double top) {
        Set<Node> set = new HashSet<>(subject);
        Map<Node, List<Node>> parents = new HashMap<>();
        Set<Node> linked = new HashSet<>();
        for (Edge e : edges) {
            if (e.from() == e.to() || !set.contains(e.from()) || !set.contains(e.to())) continue;
            parents.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            linked.add(e.from());
            linked.add(e.to());
        }

        Map<Node, Integer> level = new HashMap<>();
        for (Node n : subject) levelOf(n, parents, level, new HashSet<>());

        Map<Integer, List<Node>> byLevel = new TreeMap<>();
        List<Node> loose = new ArrayList<>();
        for (Node n : subject) {
            if (linked.contains(n)) byLevel.computeIfAbsent(level.get(n), k -> new ArrayList<>()).add(n);
            else loose.add(n);
        }

        double x = 0;
        double bottom = top;
        Map<Node, Double> order = new HashMap<>();
        for (List<Node> column : byLevel.values()) {
            // Sit near the tables this one points at, to keep curves short.
            column.sort(Comparator.<Node>comparingDouble(n -> {
                List<Node> ps = parents.getOrDefault(n, List.of());
                return ps.stream().filter(order::containsKey).mapToDouble(order::get).average().orElse(1e9);
            }).thenComparing(n -> n.table.name(), String.CASE_INSENSITIVE_ORDER));

            double y = top;
            int width = 0;
            for (Node n : column) {
                if (y > top && y + n.h - top > MAX_COLUMN_HEIGHT) {
                    x += width + COLUMN_GAP / 2.0;
                    y = top;
                    width = 0;
                }
                n.x = x;
                n.y = y;
                order.put(n, y + n.h / 2.0);
                y += n.h + ROW_GAP;
                width = Math.max(width, n.w);
                bottom = Math.max(bottom, y);
            }
            x += width + COLUMN_GAP;
        }

        // Tables with no relationships: a wrapped strip underneath.
        double lx = 0;
        double ly = byLevel.isEmpty() ? top : bottom + ROW_GAP;
        double rowHeight = 0;
        double wrapAt = Math.max(x, 1400);
        loose.sort(Comparator.comparing(n -> n.table.name(), String.CASE_INSENSITIVE_ORDER));
        for (Node n : loose) {
            if (lx > 0 && lx + n.w > wrapAt) {
                lx = 0;
                ly += rowHeight + ROW_GAP;
                rowHeight = 0;
            }
            n.x = lx;
            n.y = ly;
            lx += n.w + 40;
            rowHeight = Math.max(rowHeight, n.h);
        }
    }

    private static int levelOf(Node n, Map<Node, List<Node>> parents, Map<Node, Integer> level, Set<Node> path) {
        Integer known = level.get(n);
        if (known != null) return known;
        if (!path.add(n)) return 0; // reference cycle
        int l = 0;
        for (Node p : parents.getOrDefault(n, List.of())) l = Math.max(l, levelOf(p, parents, level, path) + 1);
        path.remove(n);
        level.put(n, l);
        return l;
    }

    private void fit() {
        if (nodes.isEmpty() || canvas.getWidth() == 0) return;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Node n : nodes.values()) {
            x0 = Math.min(x0, n.x);
            y0 = Math.min(y0, n.y);
            x1 = Math.max(x1, n.x + n.w);
            y1 = Math.max(y1, n.y + n.h);
        }
        double margin = 36;
        double sx = (canvas.getWidth() - 2 * margin) / Math.max(1, x1 - x0);
        double sy = (canvas.getHeight() - 2 * margin) / Math.max(1, y1 - y0);
        scale = Math.max(0.15, Math.min(1, Math.min(sx, sy)));
        tx = margin - x0 * scale + Math.max(0, (canvas.getWidth() - 2 * margin - (x1 - x0) * scale) / 2);
        ty = margin - y0 * scale;
        needFit = false;
        canvas.repaint();
    }

    private void zoomTo(double s) {
        zoomAt(canvas.getWidth() / 2.0, canvas.getHeight() / 2.0, s / scale);
    }

    private void zoomAt(double mx, double my, double factor) {
        double wx = (mx - tx) / scale, wy = (my - ty) / scale;
        scale = Math.max(0.15, Math.min(2.5, scale * factor));
        tx = mx - wx * scale;
        ty = my - wy * scale;
        canvas.repaint();
    }

    private Node nodeAt(Point p) {
        double wx = (p.x - tx) / scale, wy = (p.y - ty) / scale;
        Node hit = null;
        for (Node n : nodes.values()) if (n.contains(wx, wy)) hit = n;
        return hit;
    }

    private String tipAt(Point p) {
        Node n = nodeAt(p);
        if (n == null) return null;
        double wy = (p.y - ty) / scale;
        int row = (int) Math.floor((wy - n.y - HEADER) / ROW);
        if (row < 0 || row >= n.table.columns().size()) {
            return n.table.kind() + " " + n.table.name();
        }
        ColumnInfo c = n.table.columns().get(row);
        ForeignKey fk = n.table.fkFor(c.name());
        if (fk == null) return c.name() + "  " + c.type() + (c.pk() > 0 ? "  · primary key" : "");
        return n.table.name() + "." + String.join(",", fk.from()) + " → " + fk.toTable() + "."
                + String.join(",", fk.to()) + "   on delete " + fk.onDelete().toLowerCase(Locale.ROOT);
    }

    // ---- painting -------------------------------------------------------------------------

    private final class Canvas extends JComponent {
        Canvas() {
            setOpaque(true);
            setToolTipText("");
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    last = e.getPoint();
                    dragging = nodeAt(e.getPoint());
                    selected = dragging;
                    if (dragging != null) {
                        // bring to front
                        nodes.remove(dragging.table.name());
                        nodes.put(dragging.table.name(), dragging);
                    }
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragging = null;
                    setCursor(Cursor.getDefaultCursor());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    double dx = e.getX() - last.x, dy = e.getY() - last.y;
                    last = e.getPoint();
                    if (dragging != null) {
                        dragging.x += dx / scale;
                        dragging.y += dy / scale;
                    } else {
                        tx += dx;
                        ty += dy;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    Node n = nodeAt(e.getPoint());
                    if (n != hover) {
                        hover = n;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hover != null) {
                        hover = null;
                        repaint();
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    Node n = nodeAt(e.getPoint());
                    if (n != null && e.getClickCount() == 2) onOpen.accept(n.table.name());
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    double r = e.getPreciseWheelRotation();
                    if (e.isControlDown() || e.isMetaDown()) {
                        zoomAt(e.getX(), e.getY(), Math.pow(1.12, -r));
                    } else {
                        if (e.isShiftDown()) tx -= r * 28;
                        else ty -= r * 28;
                        repaint();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            return tipAt(e.getPoint());
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (needFit && getWidth() > 0) fit();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Theme.bg);
            g.fillRect(0, 0, getWidth(), getHeight());

            if (nodes.isEmpty()) {
                g.setColor(Theme.muted);
                g.setFont(Theme.ui);
                g.drawString("No tables yet.", 24, 36);
                g.dispose();
                return;
            }

            g.translate(tx, ty);
            g.scale(scale, scale);
            Rectangle2D view = new Rectangle2D.Double(-tx / scale, -ty / scale, getWidth() / scale, getHeight() / scale);

            Node focus = selected != null ? selected : hover;
            Set<Node> related = new HashSet<>();
            if (focus != null) {
                related.add(focus);
                for (Edge e : edges) {
                    if (e.from() == focus) related.add(e.to());
                    if (e.to() == focus) related.add(e.from());
                }
            }

            for (Edge e : edges) {
                boolean active = focus != null && (e.from() == focus || e.to() == focus);
                if (!active) paintEdge(g, e, false, focus != null);
            }
            long now = System.currentTimeMillis();
            for (Node n : nodes.values()) {
                if (!view.intersects(n.x - 4, n.y - 4, n.w + 8, n.h + 8)) continue;
                paintNode(g, n, focus != null && !related.contains(n), n == focus, now);
            }
            // Active relationships go on top of the cards so they can be followed end to end.
            for (Edge e : edges) {
                if (focus != null && (e.from() == focus || e.to() == focus)) paintEdge(g, e, true, false);
            }
            g.dispose();
        }

        private void paintEdge(Graphics2D g, Edge e, boolean active, boolean dimmed) {
            Node a = e.from(), b = e.to();
            double ay = a.y + HEADER + e.fromRow() * ROW + ROW / 2.0;
            double by = e.toRow() >= 0 ? b.y + HEADER + e.toRow() * ROW + ROW / 2.0 : b.y + HEADER / 2.0;
            double ax, bx, c1, c2;
            if (a == b || Math.abs(a.cx() - b.cx()) < (a.w + b.w) / 4.0) {
                // same column (or self reference): loop out to the right
                ax = a.x + a.w;
                bx = b.x + b.w;
                double bulge = 46 + Math.abs(ay - by) * 0.15;
                c1 = ax + bulge;
                c2 = bx + bulge;
            } else if (a.cx() > b.cx()) {
                ax = a.x;
                bx = b.x + b.w;
                double d = Math.max(40, (ax - bx) * 0.5);
                c1 = ax - d;
                c2 = bx + d;
            } else {
                ax = a.x + a.w;
                bx = b.x;
                double d = Math.max(40, (bx - ax) * 0.5);
                c1 = ax + d;
                c2 = bx - d;
            }

            Color base = Theme.blend(Theme.border, Theme.muted, 0.55f);
            g.setColor(active ? Theme.accent : dimmed ? Theme.blend(Theme.bg, base, 0.4f) : base);
            g.setStroke(new BasicStroke(active ? 1.8f : 1.1f));
            g.draw(new CubicCurve2D.Double(ax, ay, c1, ay, c2, by, bx, by));

            // referencing end: dot. referenced end: arrow head.
            g.fill(new Ellipse2D.Double(ax - 2.5, ay - 2.5, 5, 5));
            double dir = c2 > bx ? 1 : -1;
            Path2D head = new Path2D.Double();
            head.moveTo(bx, by);
            head.lineTo(bx + dir * 7, by - 3.5);
            head.lineTo(bx + dir * 7, by + 3.5);
            head.closePath();
            g.fill(head);
        }

        private void paintNode(Graphics2D g, Node n, boolean dimmed, boolean focused, long now) {
            var saved = g.getComposite();
            if (dimmed) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.38f));

            RoundRectangle2D box = new RoundRectangle2D.Double(n.x, n.y, n.w, n.h, 10, 10);
            g.setColor(Theme.surface);
            g.fill(box);

            Color head = Theme.raised;
            Long flashed = flashes.get(n.table.name());
            if (flashed != null) {
                float age = (now - flashed) / (float) FLASH_MS;
                head = Theme.blend(Theme.raised, Theme.upd, 0.6f * (1 - age));
            }
            var clip = g.getClip();
            g.clip(box);
            g.setColor(head);
            g.fill(new Rectangle2D.Double(n.x, n.y, n.w, HEADER));
            g.setClip(clip);

            g.setColor(focused ? Theme.muted : Theme.border);
            g.setStroke(new BasicStroke(focused ? 1.3f : 1f));
            g.draw(box);

            // Text is unreadable when zoomed far out; cards and curves still show the shape.
            if (scale >= 0.3) {
                boolean view = !n.table.isTable();
                g.setFont(Theme.monoBold);
                FontMetrics fm = g.getFontMetrics();
                g.setColor(Theme.text);
                g.drawString(n.table.name(), (float) n.x + 9, (float) n.y + 18);

                g.setFont(Theme.small);
                g.setColor(Theme.muted);
                Long count = counts.get(n.table.name());
                String tag = view ? n.table.kind() : count == null ? "" : Format.count(count);
                g.drawString(tag, (float) (n.x + n.w - 9 - g.getFontMetrics().stringWidth(tag)), (float) n.y + 18);

                g.setFont(Theme.mono);
                fm = g.getFontMetrics();
                List<ColumnInfo> cols = n.table.columns();
                for (int i = 0; i < cols.size(); i++) {
                    ColumnInfo c = cols.get(i);
                    float y = (float) n.y + HEADER + i * ROW;
                    float base = y + 14;
                    boolean fk = n.table.fkFor(c.name()) != null;

                    if (c.pk() > 0) {
                        g.setColor(Theme.muted);
                        Path2D d = new Path2D.Double();
                        double mx = n.x + 11, my = y + ROW / 2.0;
                        d.moveTo(mx, my - 3.5);
                        d.lineTo(mx + 3.5, my);
                        d.lineTo(mx, my + 3.5);
                        d.lineTo(mx - 3.5, my);
                        d.closePath();
                        g.fill(d);
                    } else if (fk) {
                        g.setColor(Theme.muted);
                        g.setStroke(new BasicStroke(1.1f));
                        g.draw(new Ellipse2D.Double(n.x + 8.5, y + ROW / 2.0 - 2.5, 5, 5));
                    }

                    String type = c.type().toLowerCase(Locale.ROOT);
                    int typeWidth = fm.stringWidth(type);
                    g.setColor(Theme.muted);
                    g.drawString(type, (float) (n.x + n.w - 9 - typeWidth), base);

                    g.setColor(fk ? Theme.accent : Theme.text);
                    g.setFont(c.pk() > 0 ? Theme.monoBold : Theme.mono);
                    String name = clipText(c.name(), g.getFontMetrics(), n.w - 22 - typeWidth - 22);
                    g.drawString(name, (float) n.x + 22, base);
                    g.setFont(Theme.mono);
                }
            }
            g.setComposite(saved);
        }

        private String clipText(String s, FontMetrics fm, int width) {
            if (fm.stringWidth(s) <= width) return s;
            int n = s.length();
            while (n > 1 && fm.stringWidth(s.substring(0, n) + "…") > width) n--;
            return s.substring(0, n) + "…";
        }
    }
}
