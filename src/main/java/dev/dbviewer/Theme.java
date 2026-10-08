package dev.dbviewer;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.util.FontUtils;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Insets;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * Look and feel. Follows the OS light/dark setting unless the user picks one; the choice is
 * remembered. Custom-painted components read their colours from the static fields here.
 */
final class Theme {
    private Theme() {}

    enum Mode { AUTO, LIGHT, DARK }

    static final int ROW_HEIGHT = 24;

    static Mode mode = Mode.AUTO;
    static boolean dark;

    static Color bg, surface, raised, border, grid, text, muted, accent, ins, upd, del;
    static Font ui, uiBold, small, mono, monoBold, link;

    private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final boolean MAC = os().contains("mac");
    private static final boolean WINDOWS = os().contains("win");

    static void init() {
        try {
            mode = Mode.valueOf(PREFS.get("theme", "AUTO"));
        } catch (IllegalArgumentException e) {
            mode = Mode.AUTO;
        }

        FlatInterFont.installLazy();
        FlatJetBrainsMonoFont.installLazy();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);

        ui = FontUtils.getCompositeFont(FlatInterFont.FAMILY, Font.PLAIN, 12);
        uiBold = ui.deriveFont(Font.BOLD);
        small = ui.deriveFont(11f);
        mono = FontUtils.getCompositeFont(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, 12);
        monoBold = mono.deriveFont(Font.BOLD);
        link = mono.deriveFont(Map.of(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_LOW_DOTTED));

        UIManager.put("defaultFont", ui);
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("TabbedPane.tabHeight", 32);
        UIManager.put("TabbedPane.tabInsets", new Insets(2, 10, 2, 10));
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);
        UIManager.put("SplitPane.dividerSize", 5);
        UIManager.put("Table.rowHeight", ROW_HEIGHT);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("TableHeader.height", 28);
        UIManager.put("Button.margin", new Insets(2, 8, 2, 8));
        UIManager.put("ToolBar.buttonMargins", new Insets(3, 8, 3, 8));

        apply();

        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "theme-watch");
            t.setDaemon(true);
            return t;
        });
        watcher.scheduleWithFixedDelay(() -> {
            if (mode == Mode.AUTO && osDark() != dark) SwingUtilities.invokeLater(Theme::apply);
        }, 4, 4, TimeUnit.SECONDS);
    }

    static void setMode(Mode m) {
        mode = m;
        PREFS.put("theme", m.name());
        apply();
    }

    static Mode nextMode() {
        return Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
    }

    static void onChange(Runnable r) {
        listeners.add(r);
    }

    static void apply() {
        dark = mode == Mode.DARK || (mode == Mode.AUTO && osDark());

        // Neutral greys only; colour is reserved for what changed (insert / update / delete).
        bg = dark ? new Color(0x171717) : new Color(0xf6f6f5);
        surface = dark ? new Color(0x1c1c1c) : new Color(0xffffff);
        raised = dark ? new Color(0x262626) : new Color(0xededec);
        border = dark ? new Color(0x2f2f2f) : new Color(0xdededc);
        grid = dark ? new Color(0x242424) : new Color(0xf0f0ef);
        text = dark ? new Color(0xe6e6e6) : new Color(0x1c1c1c);
        muted = dark ? new Color(0x8c8c8c) : new Color(0x737373);
        accent = text;
        ins = dark ? new Color(0x74c08f) : new Color(0x2f7d4f);
        upd = dark ? new Color(0xd8aa55) : new Color(0xa2660f);
        del = dark ? new Color(0xe07f7f) : new Color(0xb83a3a);
        Color selection = dark ? new Color(0x333333) : new Color(0xe3e3e1);

        Map<String, String> extra = new HashMap<>();
        extra.put("@background", hex(bg));
        extra.put("@foreground", hex(text));
        extra.put("@accentColor", hex(dark ? new Color(0x9a9a9a) : new Color(0x5c5c5c)));
        extra.put("@selectionBackground", hex(selection));
        extra.put("@selectionForeground", hex(text));
        extra.put("@selectionInactiveBackground", hex(selection));
        extra.put("@selectionInactiveForeground", hex(text));
        FlatLaf.setGlobalExtraDefaults(extra);

        for (String k : List.of("Table", "List", "Tree", "TextArea", "TextPane", "EditorPane")) {
            UIManager.put(k + ".background", surface);
        }
        UIManager.put("Table.gridColor", grid);
        UIManager.put("TableHeader.background", surface);
        UIManager.put("TableHeader.separatorColor", surface);
        UIManager.put("TableHeader.bottomSeparatorColor", border);
        UIManager.put("Component.borderColor", border);
        UIManager.put("Component.focusedBorderColor", muted);
        UIManager.put("Component.focusColor", border);
        UIManager.put("Separator.foreground", border);
        UIManager.put("SplitPaneDivider.draggingColor", border);
        UIManager.put("TabbedPane.contentAreaColor", border);
        UIManager.put("TabbedPane.underlineColor", text);
        UIManager.put("TabbedPane.inactiveUnderlineColor", muted);
        UIManager.put("TabbedPane.hoverColor", raised);
        UIManager.put("TabbedPane.focusColor", surface);
        UIManager.put("Button.default.background", raised);
        UIManager.put("Button.default.foreground", text);

        if (dark) FlatDarkLaf.setup();
        else FlatLightLaf.setup();

        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame jf) styleWindow(jf);
        }
        FlatLaf.updateUI();
        for (Runnable r : listeners) r.run();
    }

    /** Keep the macOS title bar in step with the theme, including a manual override. */
    static void styleWindow(JFrame f) {
        if (!MAC) return;
        f.getRootPane().putClientProperty("apple.awt.windowAppearance",
                dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        f.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
    }

    /** Hairline border that always paints in the current theme's border colour. */
    static javax.swing.border.Border line(int top, int left, int bottom, int right) {
        return new javax.swing.border.MatteBorder(top, left, bottom, right, Color.GRAY) {
            @Override
            public void paintBorder(java.awt.Component c, java.awt.Graphics g, int x, int y, int w, int h) {
                Insets i = getBorderInsets(c);
                g.setColor(border);
                g.fillRect(x, y, w, i.top);
                g.fillRect(x, y + h - i.bottom, w, i.bottom);
                g.fillRect(x, y, i.left, h);
                g.fillRect(x + w - i.right, y, i.right, h);
            }
        };
    }

    /** Dense-grid defaults shared by every table: left-aligned headers, fixed row height. */
    static void dense(javax.swing.JTable t) {
        t.setFont(mono);
        t.setRowHeight(ROW_HEIGHT);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
        if (t.getTableHeader().getDefaultRenderer() instanceof javax.swing.JLabel l) {
            l.setHorizontalAlignment(javax.swing.SwingConstants.LEADING);
        }
    }

    static Color blend(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static Color op(Change.Op op) {
        return switch (op) {
            case INSERT -> ins;
            case UPDATE -> upd;
            case DELETE -> del;
            case INFO -> muted;
        };
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private static boolean osDark() {
        try {
            if (MAC) {
                return run("defaults", "read", "-g", "AppleInterfaceStyle").toLowerCase(Locale.ROOT).contains("dark");
            }
            if (WINDOWS) {
                return run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").contains("0x0");
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
        return out;
    }
}
