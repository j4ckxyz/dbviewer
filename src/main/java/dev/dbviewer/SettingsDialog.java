package dev.dbviewer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;

/** Settings: appearance and updates. */
final class SettingsDialog extends JDialog {

    private final MainWindow win;
    private final JLabel status = new JLabel(" ");
    private final JButton check = new JButton("Check now");
    private final JButton install = new JButton("Install and restart");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private Updater.Release release;

    SettingsDialog(MainWindow win, Updater.Release known) {
        super(win, "Settings", true);
        this.win = win;
        setResizable(false);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        col.add(heading("Appearance"));
        JComboBox<String> theme = new JComboBox<>(new String[] {"Follow system", "Light", "Dark"});
        theme.setSelectedIndex(Theme.mode.ordinal());
        theme.addActionListener(e -> Theme.setMode(Theme.Mode.values()[theme.getSelectedIndex()]));
        col.add(row(new JLabel("Theme"), theme));
        col.add(Box.createVerticalStrut(18));

        col.add(heading("Updates"));
        JLabel version = new JLabel("DBViewer " + Updater.VERSION);
        col.add(row(version));
        JCheckBox auto = new JCheckBox("Check for updates automatically", Updater.auto());
        auto.addActionListener(e -> Updater.setAuto(auto.isSelected()));
        col.add(row(auto));
        check.addActionListener(e -> checkNow());
        install.addActionListener(e -> installNow());
        install.setVisible(false);
        col.add(row(check, install));
        progress.setVisible(false);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(Box.createVerticalStrut(6));
        col.add(progress);
        status.setForeground(Theme.muted);
        col.add(row(status));

        setContentPane(col);
        if (known != null) offer(known);
        pack();
        setSize(Math.max(getWidth(), 440), getHeight());
        setLocationRelativeTo(win);
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.uiBold);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        return l;
    }

    private static JComponent row(JComponent... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(10));
            p.add(parts[i]);
        }
        p.add(Box.createHorizontalGlue());
        return p;
    }

    private void say(String text) {
        status.setText("<html><body style='width:300px'>" + text + "</body></html>");
    }

    private void offer(Updater.Release r) {
        release = r;
        status.setForeground(Theme.text);
        if (!Updater.canInstall()) {
            say("Version " + r.version() + " is available. Running from source, so install it from the release page.");
        } else if (r.assetUrl() == null) {
            say("Version " + r.version() + " is available, but has no download for this platform.");
        } else {
            say("Version " + r.version() + " is available.");
            install.setVisible(true);
        }
    }

    private void checkNow() {
        check.setEnabled(false);
        install.setVisible(false);
        status.setForeground(Theme.muted);
        say("Checking…");
        Thread t = new Thread(() -> {
            try {
                Updater.Release r = Updater.latest();
                SwingUtilities.invokeLater(() -> {
                    check.setEnabled(true);
                    if (r != null && Updater.newer(r.version(), Updater.VERSION)) {
                        offer(r);
                        win.updateAvailable(r);
                    } else {
                        say("You have the latest version.");
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    check.setEnabled(true);
                    status.setForeground(Theme.del);
                    say("Could not check: " + e.getMessage());
                });
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private void installNow() {
        Updater.Release r = release;
        if (r == null) return;
        install.setEnabled(false);
        check.setEnabled(false);
        progress.setValue(0);
        progress.setVisible(true);
        status.setForeground(Theme.muted);
        say("Downloading " + r.version() + "…");
        Thread t = new Thread(() -> {
            try {
                Updater.install(r, pct -> SwingUtilities.invokeLater(() -> progress.setValue(pct)));
                SwingUtilities.invokeLater(win::quit);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    install.setEnabled(true);
                    check.setEnabled(true);
                    progress.setVisible(false);
                    status.setForeground(Theme.del);
                    say("Update failed: " + e.getMessage());
                });
            }
        }, "update-install");
        t.setDaemon(true);
        t.start();
    }
}
