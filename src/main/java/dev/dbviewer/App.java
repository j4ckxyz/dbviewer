package dev.dbviewer;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.nio.file.Path;

public final class App {
    private App() {}

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "DBViewer");
        System.setProperty("apple.awt.application.appearance", "system");

        SwingUtilities.invokeLater(() -> {
            Theme.init();
            MainWindow window = new MainWindow();
            window.setVisible(true);
            // usage: DBViewer [database [table]]
            if (args.length > 0) window.open(Path.of(args[0]), args.length > 1 ? args[1] : null);

            // macOS "Open With" / dropping a file on the dock icon
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                Desktop.getDesktop().setOpenFileHandler(e -> {
                    if (!e.getFiles().isEmpty()) window.open(e.getFiles().get(0).toPath(), false);
                });
            }
        });
    }
}
