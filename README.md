# LifeApp — My Journal

An offline-first personal journal, primarily for Xiaomi Pad 7 and a compatible stylus.
Phase 3.1 corrects tablet workspace/ink alignment and adds recoverable journal-page deletion.
Phase 3 implements the Android Daily Journal, handwritten Monthly Planner and
customizable Monthly Habit Tracker, Free Notebook and local export/backup/restore. No account or network connection is needed.
Notebook, To-do and Settings are functional on Android. Future companion access is planned through a browser PWA.

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

## To-do, reminders and Drive backup

Create and edit tasks offline in To-do. Tasks have a title and calendar deadline.
Complete or reopen them with the checkbox. Deletion requires confirmation. Settings
offers two optional local reminders, defaulting to 09:00 and 19:00 when enabled.
[To-do and reminder behavior](docs/todo-reminders.md)

Settings also supports manually creating, listing and restoring encrypted Google
Drive backups after OAuth setup. A recovery passphrase is required for each cloud
backup. Local backup and restore remain available without an account. The shared
To-do synchronization protocol is exercised against independent databases and a
fake provider. Live synchronization is disabled.
[Drive setup and recovery](docs/drive-backup.md) · [Sync protocol and limits](docs/phase-4-sync.md)

## Setup and build

Use JDK 21 and stable Android Studio supporting AGP 9.3. Install SDK platform 37.0,
Build Tools 36.0.0 and platform-tools. Configure ANDROID_HOME or an untracked
local.properties containing sdk.dir for your installation. On Windows use forward
slashes in that path and `gradlew.bat` instead of `./gradlew`.

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest :shared:ui:jvmTest \
  :apps:android:assembleDebug :apps:android:lintDebug \
  :apps:android:assembleDebugAndroidTest
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
# Boot a disposable test emulator/device before running:
./gradlew :apps:android:connectedDebugAndroidTest
```

The debug APK is under apps/android/build/outputs/apk/debug/. GitHub Actions uploads
it as my-journal-ci-debug-ephemeral plus test/lint reports, and runs Android input/UI tests on a
hardware-accelerated API 36 emulator. Initial Gradle/SDK downloads need internet;
core editing and local backups work offline. Drive backup is optional. Debug APKs use builder-specific
signing keys; retain the same signing key for in-place updates. Local backup/export is available in Settings. See [validation](docs/validation.md)
for the actual checks and [stylus testing](docs/stylus-testing.md) for remaining
physical Xiaomi validation. Do not infer device behavior from compilation alone.

## Modules

| Module | Responsibility |
| --- | --- |
| apps/android | Android composition, three editor screens, native stylus/Ink adapter |
| apps/desktop | Unsupported legacy JVM shell retained for compatibility checks |
| apps/ios | Unsupported legacy SwiftUI host |
| shared/core | UUID/UTC metadata and revision invariants |
| shared/domain | Portable records, calendar geometry, typed routes and editor state/history |
| shared/data | Room schema 7, explicit migrations, codecs and local repositories |
| shared/ui | Shared adaptive navigation, paper colors and typography |

Android is the supported app. Phase 6 targets an installable PWA for iPhone and
desktop browsers, with a self-hosted Ktor/PostgreSQL service. Legacy launcher
sources remain available and Apple targets stay opt-in. Android CI requires no
Apple toolchain.

Phase 5 adds a server-oriented To-do protocol tested against a fake transport,
durable checkpoints and shared workout models. Android starts unconfigured.
See [server architecture](docs/server-architecture.md) and the
[Phase 6 plan](docs/phase-6-implementation-plan.md).

See [architecture](docs/architecture.md), [handwriting format](docs/handwriting-format.md),
[versions](docs/versions.md), [roadmap](docs/roadmap.md) and [development rules](AGENTS.md).

## Self-hosted API development

Phase 6A provides the Ktor/PostgreSQL To-do API and local integration workflow.
See [server development](docs/server-installation.md) for isolated tests and
[authentication](docs/server-authentication.md) for session and access rules.

Phase 6B adds the installable browser To-do app with IndexedDB and same-origin
sync. [PWA setup and iPhone checklist](docs/pwa-installation.md)

## Persistent install/update APK

Download **my-journal-installable** from a successful trusted main/bootstrap signing
run. Future APKs from this channel use the same GitHub-secret-backed identity,
verified against the public pin. Do not use the ephemeral CI-debug artifact for
updates. See [persistent signing](docs/persistent-apk-signing.md) for key recovery,
artifact selection and the existing Phase3 signing mismatch. Keep the saved backup
and installed app intact while planning the first transition to this new identity.
