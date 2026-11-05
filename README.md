# LifeApp — My Journal

An offline-first personal journal, primarily for Xiaomi Pad 7 and a compatible stylus.
Phase 3.1 corrects tablet workspace/ink alignment and adds recoverable journal-page deletion.
Phase 3 implements the Android Daily Journal, handwritten Monthly Planner and
customizable Monthly Habit Tracker, Free Notebook and local export/backup/restore. No account or network connection is needed.
Notebook and Settings are functional on Android. Desktop/iOS launchers
are prepared; their editing destinations remain placeholders.

## Journal, planner, habits and notebooks

The Daily Journal opens today's local date. Navigate days, pick a date, add pages,
and write with pen/highlighter or a whole-stroke eraser. Fingers pan and pinch;
Undo/Redo restores exact vector strokes. Saved dates/pages load lazily from Room.

The Monthly Planner is a Monday-first paper calendar with five or six rows.
Previous/next, current month and a month/year picker navigate independent calendars.
The same native Ink engine writes inside date cells, with automatic local saving,
erasing and Undo/Redo. Landscape fits the page; portrait starts zoomed for readable
cells and supports panning. Finger taps on date numbers open that exact journal.
Back returns to the planner month and viewport; opening a blank date creates no
stored journal entry until a stroke or explicit Add Page.

The Habit Tracker has dates down the rows, named habits across columns and two-state
checkboxes. Add or rename habits, reorder them, set optional categories/colours/targets,
and archive/restore columns without losing completion history. Each month has its own
configuration. New months offer explicit blank setup or copying active definitions
from the previous configured month, with fresh IDs and no checkmarks. Targets copied
to a shorter month are limited to its day count. Totals use the entire month as the
percentage denominator. The date column and header stay visible while scrolling.
Tap a day for its journal; Back restores the ledger position. The journal's “View
monthly habits” action opens the corresponding month.

Completed gestures and checkbox changes save automatically in transactions. Per-feature
saved status appears only after its writes succeed; pending counts and errors remain
visible across module navigation. Retry retains the original edit targets. Wait for
successful saving before force stopping: a process kill before commit can lose pending
or in-progress work. Saved vectors remain editable after restart. Undo history is
session-only and may be unloaded with saved pages under the bounded memory cache.

Use **Delete page** to move the current journal page into a recoverable archive after
confirmation; **Restore pages** returns it at its original order with all vectors.
The last active page cannot be deleted. All pages, including archived pages, remain
in backups/structured exports and labelled PDF pages.

Tablet drawing now uses one explicit page matrix for input, paper and wet/dry ink.
Fit Width fills the writing width; Calendar offers Fit Calendar and Calendar Writing
Width, with pan/zoom for portrait. No stored point coordinates are rewritten.
See [alignment details](docs/tablet-ink-alignment.md) and
[safe Android upgrades](docs/safe-android-upgrade.md) before updating an installed app.
A matching signing key and a verified Phase3 backup are essential; never uninstall
to work around a signature mismatch.

## Notebook and data protection

Create named notebooks with descriptions, ordered/titled pages and blank, ruled,
dotted or grid paper. Change templates without moving ink. Rename, reorder,
archive and restore pages/notebooks; there is no permanent-delete button. Notebook
pages use the same pen/highlighter/eraser, colour/width, Undo/Redo and autosave engine.
Only the selected page's vectors load, with protected bounded saved caches.

Settings → Backup & Restore provides:

- PDF: a journal date or date range, planner month, notebook page or entire notebook.
- Structured JSON and native vector ZIP: portable records and complete LJPT1 ink.
- Markdown: titles and metadata only, with an explicit no-recognition limitation.
- Full local backup: all committed modules, archived records and exact metadata.
- Restore: validate and review a full backup before confirming atomic replacement.

All destinations use Android's system file picker without broad storage permissions.
Choose local storage for offline use. Operations wait for every editor save queue;
errors block a complete backup. A populated restore requires a separately chosen,
verified recovery backup before changing the database. Do not select your input
backup as the recovery destination. No merging is implemented. Keep recovery files
until you have checked restored data. The UI locks during operations and rebuilds
all editor caches after restore. Existing editable vectors are unaffected by exports.

Backups are plain, unencrypted ZIP files: store them privately. Limits are explicit
and oversized operations fail instead of silently omitting data. PDF paths approximate
Ink brush edge smoothing/pressure; they do not convert handwriting to text. See
[export formats](docs/export-formats.md), [backup format](docs/backup-format.md) and
[backup/restore instructions](docs/backup-restore.md).

## Setup and build

Use JDK 21 and stable Android Studio supporting AGP 9.3. Install SDK platform 37.0,
Build Tools 36.0.0 and platform-tools. Configure ANDROID_HOME or an untracked
local.properties containing sdk.dir for your installation. On Windows use forward
slashes in that path and `gradlew.bat` instead of `./gradlew`.

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest :shared:ui:jvmTest \
  :apps:android:assembleDebug :apps:android:lintDebug :apps:desktop:classes \
  :apps:android:assembleDebugAndroidTest
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
# Boot a disposable test emulator/device before running:
./gradlew :apps:android:connectedDebugAndroidTest
# Desktop shell:
./gradlew :apps:desktop:run
```

The debug APK is under apps/android/build/outputs/apk/debug/. GitHub Actions uploads
it as my-journal-debug plus test/lint reports, and runs Android input/UI tests on a
hardware-accelerated API 36 emulator. Initial Gradle/SDK downloads need internet;
the application itself uses no online services. Debug APKs use builder-specific
signing keys; retain the same signing key for in-place updates. Local backup/export is available in Settings. See [validation](docs/validation.md)
for the actual checks and [stylus testing](docs/stylus-testing.md) for remaining
physical Xiaomi validation. Do not infer device behavior from compilation alone.

## Modules

| Module | Responsibility |
| --- | --- |
| apps/android | Android composition, three editor screens, native stylus/Ink adapter |
| apps/desktop | JVM launcher for the shared shell |
| apps/ios | Prepared SwiftUI/Xcode host |
| shared/core | UUID/UTC metadata and revision invariants |
| shared/domain | Portable records, calendar geometry, typed routes and editor state/history |
| shared/data | Room schema 5, explicit migrations, codec and local repositories |
| shared/ui | Shared adaptive navigation, paper colors and typography |

Apple targets are disabled by default on every host. On macOS with Xcode 26.4 use
`./gradlew -PenableIos=true :shared:ui:linkDebugFrameworkIosSimulatorArm64` and open
apps/ios/MyJournal.xcodeproj. iOS editing/viewing is outside Phase 3. Android SDK
configuration is still required when configuring this full Gradle project.

See [architecture](docs/architecture.md), [handwriting format](docs/handwriting-format.md),
[versions](docs/versions.md), [roadmap](docs/roadmap.md) and [development rules](AGENTS.md).
