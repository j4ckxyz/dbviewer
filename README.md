# DBViewer

Native desktop tool (Java Swing, no webview) for developing against local SQLite databases.

- **Live**: commits from any process show up as row-level inserts, updates and deletes, with changed cells highlighted in the grid.
- **Relationships**: diagram of every table and foreign key; follow a key from a cell to the row it references, or to the rows that reference it.
- **Restore**: revert individual changes from the change log, or take and restore whole-database snapshots.
- **SQL console**: run scripts or a selection; `.tables`, `.schema`, `.indexes`, `.databases`.

## Build and run

Needs JDK 21 or newer (the packaged app bundles its own trimmed runtime).

```sh
./gradlew run --args="path/to/app.db"   # run from source
./gradlew test
./gradlew packageApp                     # build/dist/app-image  (DBViewer.app, or DBViewer\DBViewer.exe)
./gradlew packageInstaller               # .dmg on macOS, .msi on Windows (needs WiX)
```

Packaging targets the OS it runs on. `DBViewer [database [table]]` opens a file (and a table) directly.

## Releases and updates

Bump `version` in `gradle.properties` and push to `main`. The `release` workflow sees a version
with no release yet, builds the macOS (Apple silicon) and Windows (x64) apps, and publishes them as
release `v<version>`. Pushes that do not change the version publish nothing.

The app checks the latest release at startup (Settings → Updates to turn that off or check by
hand) and can download and install it in place. Builds are not code-signed: on first launch macOS
needs right-click → Open, and Windows SmartScreen needs "Run anyway".

## How live tracking works

A dedicated connection polls `PRAGMA data_version`. When it moves, each tracked table is diffed
against a shadow copy held in a private temp database, so nothing is written to your file.
Tables over 200,000 rows (or past 1.5M rows in total) are not shadowed; only their row counts
are watched. Snapshots use SQLite's online backup API and live in the per-user app data folder.
