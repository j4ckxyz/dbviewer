package dev.dbviewer;

import dev.dbviewer.Schema.TableInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.prefs.Preferences;

/** The application window: table list, tabs (relationships, SQL, tables), changes and snapshots. */
final class MainWindow extends JFrame implements Monitor.Listener {

    private static final Preferences PREFS = Preferences.userNodeForPackage(MainWindow.class);
    private static final int MOD = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private Db db;
    private Monitor monitor;
    private Schema schema;
    private Set<String> untracked = Set.of();
    private final Map<String, TablePanel> open = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JPanel welcome = new JPanel(new GridBagLayout());
    private final Sidebar sidebar = new Sidebar(name -> openTable(name, null));
    private final JTabbedPane tabs = new JTabbedPane();
    private final GraphPanel graph = new GraphPanel(name -> openTable(name, null));
    private final SqlPanel sql = new SqlPanel(this);
    private final JTabbedPane bottom = new JTabbedPane();
    private final ChangesPanel changes = new ChangesPanel(this, n -> bottom.setTitleAt(0, n == 0 ? "Changes" : "Changes (" + n + ")"));
    private final SnapshotsPanel snapshots = new SnapshotsPanel(this);

    private final JLabel title = new JLabel("No database");
    private final JLabel live = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel infoLabel = new JLabel();
    private final JButton pause = new JButton("Pause");
    private final JButton snapshot = new JButton("Snapshot");
    private final JButton theme = new JButton();
    private final JButton update = new JButton();
    private Updater.Release available;
    private String pendingTable;
    private final JMenu recentMenu = new JMenu("Open Recent");

    MainWindow() {
        super("DBViewer");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        Theme.styleWindow(this);
        java.net.URL icon = MainWindow.class.getResource("/icon.png");
        if (icon != null) {
            java.awt.Image image = Toolkit.getDefaultToolkit().getImage(icon);
            setIconImage(image);
            // Dock icon when run unpackaged; the packaged app already carries its own.
            if (java.awt.Taskbar.isTaskbarSupported()
                    && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                java.awt.Taskbar.getTaskbar().setIconImage(image);
            }
        }

        tabs.addTab("Relationships", graph);
        tabs.addTab("SQL", sql);
        graph.putClientProperty("JTabbedPane.tabClosable", false);
        sql.putClientProperty("JTabbedPane.tabClosable", false);
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (BiConsumer<JTabbedPane, Integer>) (t, i) -> closeTab(i));
        tabs.putClientProperty("JTabbedPane.tabLayoutPolicy", "scroll");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener(e -> {
            Component c = tabs.getSelectedComponent();
            if (c instanceof TablePanel p) {
                p.shown();
                sidebar.select(p.tableName());
            }
        });

        bottom.addTab("Changes", changes);
        bottom.addTab("Snapshots", snapshots);

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, bottom);
        vertical.setResizeWeight(0.72);
        vertical.setBorder(BorderFactory.createEmptyBorder());
        bottom.setPreferredSize(new Dimension(100, 230));
        sidebar.setBorder(Theme.line(0, 0, 0, 1));
        JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, vertical);
        horizontal.setBorder(BorderFactory.createEmptyBorder());
        horizontal.setDividerLocation(230);

        root.add(welcome, "welcome");
        root.add(horizontal, "main");

        JPanel content = new JPanel(new BorderLayout());
        content.add(buildToolbar(), BorderLayout.NORTH);
        content.add(root, BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(content);
        setJMenuBar(buildMenu());
        buildWelcome();
        updateChrome();
        Theme.onChange(this::updateChrome);

        getRootPane().setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) open(files.get(0).toPath(), false);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });

        // Below this the toolbar and the three panes stop fitting; the window cannot go smaller.
        Dimension min = new Dimension(900, 600);
        setMinimumSize(min);
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension want = new Dimension(PREFS.getInt("w", 1360), PREFS.getInt("h", 860));
        String forced = System.getProperty("dbviewer.size"); // e.g. -Ddbviewer.size=900x600
        if (forced != null && forced.matches("\\d+x\\d+")) {
            want = new Dimension(Integer.parseInt(forced.split("x")[0]), Integer.parseInt(forced.split("x")[1]));
        }
        setSize(Math.max(min.width, Math.min(want.width, screen.width)),
                Math.max(min.height, Math.min(want.height, screen.height)));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop d = java.awt.Desktop.getDesktop();
            if (d.isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler(e -> showSettings());
            if (d.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler((e, response) -> {
                    response.cancelQuit();
                    quit();
                });
            }
        }
        if (Updater.auto()) checkForUpdate();
    }

    void quit() {
        PREFS.putInt("w", getWidth());
        PREFS.putInt("h", getHeight());
        closeDb();
        dispose();
        System.exit(0);
    }

    private void showSettings() {
        new SettingsDialog(this, available).setVisible(true);
    }

    void updateAvailable(Updater.Release release) {
        available = release;
        update.setText("Update to " + release.version());
        update.setVisible(true);
    }

    /** Quiet background check at startup; failures (offline, rate limit) are not worth a message. */
    private void checkForUpdate() {
        Thread t = new Thread(() -> {
            try {
                Updater.Release r = Updater.latest();
                if (r != null && Updater.newer(r.version(), Updater.VERSION)) {
                    SwingUtilities.invokeLater(() -> updateAvailable(r));
                }
            } catch (Exception ignored) { }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    // ---- chrome ---------------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createCompoundBorder(Theme.line(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JButton openButton = new JButton("Open");
        openButton.addActionListener(e -> chooseAndOpen(false));
        bar.add(openButton);
        bar.add(Box.createHorizontalStrut(12));
        title.setFont(Theme.uiBold);
        bar.add(title);
        bar.add(Box.createHorizontalGlue());

        live.setFont(Theme.small);
        bar.add(live);
        bar.add(Box.createHorizontalStrut(10));
        pause.setToolTipText("Stop watching for changes; resuming reports everything that happened meanwhile");
        pause.addActionListener(e -> {
            if (monitor == null) return;
            monitor.setPaused(!monitor.isPaused());
            updateChrome();
        });
        snapshot.setToolTipText("Save a restorable copy of the whole database");
        snapshot.addActionListener(e -> {
            bottom.setSelectedComponent(snapshots);
            snapshots.takeSnapshot();
        });
        theme.setToolTipText("Theme: follow the system, or force light or dark");
        theme.addActionListener(e -> Theme.setMode(Theme.nextMode()));
        JButton settings = new JButton("Settings");
        settings.addActionListener(e -> showSettings());
        update.setVisible(false);
        update.addActionListener(e -> showSettings());
        for (JButton b : List.of(openButton, pause, snapshot, theme, settings, update)) b.setFocusable(false);
        bar.add(update);
        bar.add(pause);
        bar.add(snapshot);
        bar.add(theme);
        bar.add(settings);
        return bar;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(Theme.line(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        statusLabel.setFont(Theme.small);
        statusLabel.setMinimumSize(new Dimension(0, 0));
        infoLabel.setFont(Theme.mono.deriveFont(11f));
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(infoLabel, BorderLayout.EAST);
        return bar;
    }

    private JMenuBar buildMenu() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(menuItem("Open…", KeyStroke.getKeyStroke(KeyEvent.VK_O, MOD), () -> chooseAndOpen(false)));
        file.add(menuItem("New Database…", KeyStroke.getKeyStroke(KeyEvent.VK_N, MOD), () -> chooseAndOpen(true)));
        file.add(recentMenu);
        file.add(menuItem("Settings…", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, MOD), this::showSettings));
        file.addSeparator();
        file.add(menuItem("Close Tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, MOD), () -> closeTab(tabs.getSelectedIndex())));
        mb.add(file);

        JMenu view = new JMenu("View");
        view.add(menuItem("Relationships", KeyStroke.getKeyStroke(KeyEvent.VK_1, MOD), () -> tabs.setSelectedComponent(graph)));
        view.add(menuItem("SQL Console", KeyStroke.getKeyStroke(KeyEvent.VK_2, MOD), () -> tabs.setSelectedComponent(sql)));
        view.addSeparator();
        for (Theme.Mode m : Theme.Mode.values()) {
            String label = m == Theme.Mode.AUTO ? "Theme: Follow System" : m == Theme.Mode.LIGHT ? "Theme: Light" : "Theme: Dark";
            view.add(menuItem(label, null, () -> Theme.setMode(m)));
        }
        mb.add(view);
        rebuildRecent();
        return mb;
    }

    private static JMenuItem menuItem(String text, KeyStroke key, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        if (key != null) i.setAccelerator(key);
        i.addActionListener(e -> action.run());
        return i;
    }

    private void buildWelcome() {
        welcome.removeAll();
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        JLabel name = new JLabel("DBViewer");
        name.setFont(Theme.uiBold.deriveFont(22f));
        JLabel sub = new JLabel("Open a SQLite database, or drop one anywhere on this window.");
        sub.setForeground(Theme.muted);
        JButton openButton = new JButton("Open database…");
        openButton.addActionListener(e -> chooseAndOpen(false));
        col.add(name);
        col.add(Box.createVerticalStrut(6));
        col.add(sub);
        col.add(Box.createVerticalStrut(16));
        col.add(openButton);
        List<String> recent = recent();
        if (!recent.isEmpty()) {
            col.add(Box.createVerticalStrut(22));
            JLabel head = new JLabel("RECENT");
            head.setFont(Theme.small);
            head.setForeground(Theme.muted);
            col.add(head);
            col.add(Box.createVerticalStrut(4));
            for (String r : recent) {
                JButton link = new JButton(r);
                link.setFont(Theme.mono);
                link.putClientProperty("JButton.buttonType", "borderless");
                link.setHorizontalAlignment(JButton.LEFT);
                link.addActionListener(e -> open(Path.of(r), false));
                col.add(link);
            }
        }
        welcome.add(col);
        welcome.revalidate();
    }

    private void updateChrome() {
        theme.setText("Theme: " + switch (Theme.mode) {
            case AUTO -> "Auto";
            case LIGHT -> "Light";
            case DARK -> "Dark";
        });
        boolean has = db != null;
        boolean paused = monitor != null && monitor.isPaused();
        pause.setEnabled(has);
        pause.setText(paused ? "Resume" : "Pause");
        snapshot.setEnabled(has);
        live.setVisible(has);
        live.setText(paused ? "Paused" : "● Live");
        live.setForeground(paused ? Theme.upd : Theme.ins);
        statusLabel.setForeground(Theme.muted);
        infoLabel.setForeground(Theme.muted);
        update.setForeground(Theme.ins);
    }

    private void updateInfo() {
        if (db == null || schema == null) {
            infoLabel.setText("");
            return;
        }
        long tables = schema.tables.stream().filter(TableInfo::isTable).count();
        infoLabel.setText("SQLite " + schema.sqliteVersion + "  ·  " + schema.journalMode + "  ·  "
                + Format.bytes(db.fileSize()) + "  ·  " + tables + (tables == 1 ? " table" : " tables"));
    }

    // ---- opening and closing --------------------------------------------------------------

    private void chooseAndOpen(boolean create) {
        JFileChooser fc = new JFileChooser(PREFS.get("dir", System.getProperty("user.home")));
        fc.setDialogTitle(create ? "New SQLite database" : "Open SQLite database");
        int r = create ? fc.showSaveDialog(this) : fc.showOpenDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        PREFS.put("dir", f.getParent() == null ? "" : f.getParent());
        if (create && f.exists()) {
            JOptionPane.showMessageDialog(this, f.getName() + " already exists. Use Open to work with it.",
                    "New database", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        open(f.toPath(), create);
    }

    /** Open a database and, once loaded, one of its tables. */
    void open(Path path, String table) {
        pendingTable = table;
        open(path, false);
    }

    void open(Path path, boolean create) {
        if (!create && !Files.isRegularFile(path)) {
            JOptionPane.showMessageDialog(this, "File not found:\n" + path, "Open database", JOptionPane.ERROR_MESSAGE);
            return;
        }
        status("Opening " + path.getFileName() + "…");
        Thread t = new Thread(() -> {
            try {
                Db opened = Db.open(path);
                SwingUtilities.invokeLater(() -> install(opened));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    status("");
                    JOptionPane.showMessageDialog(this, "Could not open " + path.getFileName() + ":\n" + e.getMessage(),
                            "Open database", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "db-open");
        t.setDaemon(true);
        t.start();
    }

    private void install(Db opened) {
        closeDb();
        db = opened;
        schema = opened.schema;
        untracked = Set.of();
        title.setText(opened.path.getFileName().toString());
        title.setToolTipText(opened.path.toString());
        setTitle("DBViewer — " + opened.path.getFileName());

        sidebar.setSchema(schema);
        graph.setSchema(schema);
        sql.setDb(db);
        changes.setDb(db);
        snapshots.setDb(db);
        tabs.setSelectedComponent(graph);
        cards.show(root, "main");

        monitor = new Monitor(opened.path, this, SwingUtilities::invokeLater);
        monitor.start();
        remember(opened.path.toString());
        updateChrome();
        updateInfo();
        status("Opened " + opened.path.getFileName());
        if (pendingTable != null) {
            String table = pendingTable;
            pendingTable = null;
            openTable(table, null);
        }
    }

    private void closeDb() {
        if (monitor != null) {
            Monitor old = monitor;
            monitor = null;
            Thread t = new Thread(old::stop, "monitor-stop");
            t.setDaemon(true);
            t.start();
        }
        for (TablePanel p : new ArrayList<>(open.values())) tabs.remove(p);
        open.clear();
        if (db != null) {
            db.close();
            db = null;
        }
    }

    private void closeTab(int index) {
        if (index < 0 || !(tabs.getComponentAt(index) instanceof TablePanel p)) return;
        open.remove(p.tableName());
        tabs.remove(p);
    }

    // ---- services for the panels ----------------------------------------------------------

    void openTable(String name, String filter) {
        if (db == null || schema == null) return;
        TableInfo info = schema.table(name);
        if (info == null) {
            status("No table named " + name);
            return;
        }
        TablePanel panel = open.get(info.name());
        if (panel == null) {
            panel = new TablePanel(this, db, info);
            open.put(info.name(), panel);
            tabs.addTab(info.name(), panel);
        }
        tabs.setSelectedComponent(panel);
        if (filter != null) panel.setFilter(filter);
    }

    void status(String message) {
        statusLabel.setText(message == null || message.isEmpty() ? " " : message);
    }

    Schema schema() {
        return schema;
    }

    boolean isTracked(String table) {
        return monitor != null && !untracked.contains(table);
    }

    /** Something was written from inside the app; make sure the grids catch up even when paused. */
    void sqlRan() {
        if (monitor != null && monitor.isPaused()) {
            for (TablePanel p : open.values()) p.dataChanged();
        }
        updateInfo();
    }

    // ---- live updates -----------------------------------------------------------------------

    @Override
    public void onTick(Monitor.Tick tick) {
        if (tick.source() != monitor || db == null) return;

        if (tick.schemaChanged()) {
            schema = tick.schema();
            db.schema = schema;
            sidebar.setSchema(schema);
            graph.setSchema(schema);
            for (TablePanel p : new ArrayList<>(open.values())) {
                TableInfo next = schema.table(p.tableName());
                if (next == null) {
                    open.remove(p.tableName());
                    tabs.remove(p);
                } else {
                    p.setInfo(next);
                }
            }
        }

        untracked = tick.untracked();
        sidebar.setCounts(tick.counts(), untracked);
        graph.setCounts(tick.counts());
        changes.add(tick.changes());

        Map<String, List<Change>> byTable = new LinkedHashMap<>();
        for (Change ch : tick.changes()) {
            if (!ch.table().isEmpty()) byTable.computeIfAbsent(ch.table(), k -> new ArrayList<>()).add(ch);
        }
        for (String table : byTable.keySet()) {
            sidebar.flash(table);
            graph.flash(table);
        }
        // Tables without listed changes may still have moved (views, untracked tables).
        for (TablePanel p : open.values()) {
            List<Change> mine = byTable.get(p.tableName());
            if (mine != null) p.onChanges(mine);
            else p.dataChanged();
        }
        if (!tick.changes().isEmpty()) {
            live.setToolTipText("Last change " + Format.clock(System.currentTimeMillis()) + " · diff took "
                    + tick.tookMs() + " ms");
        }
        updateInfo();
    }

    @Override
    public void onMonitorError(Monitor source, String message) {
        if (source == monitor) status("Live tracking: " + message + " (retrying)");
    }

    // ---- recent files ---------------------------------------------------------------------

    private static List<String> recent() {
        List<String> out = new ArrayList<>();
        for (String s : PREFS.get("recent", "").split("\n")) {
            if (!s.isBlank() && Files.isRegularFile(Path.of(s))) out.add(s);
        }
        return out;
    }

    private void remember(String path) {
        List<String> list = recent();
        list.remove(path);
        list.add(0, path);
        if (list.size() > 8) list = list.subList(0, 8);
        PREFS.put("recent", String.join("\n", list));
        rebuildRecent();
        buildWelcome();
    }

    private void rebuildRecent() {
        recentMenu.removeAll();
        List<String> list = recent();
        recentMenu.setEnabled(!list.isEmpty());
        for (String r : list) recentMenu.add(menuItem(r, null, () -> open(Path.of(r), false)));
    }
}
