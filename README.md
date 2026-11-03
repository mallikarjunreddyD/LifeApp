# LifeApp — My Journal

An offline-first personal journal, primarily for Xiaomi Pad 7 and a compatible stylus.
Phase 2 implements the Android Daily Journal, handwritten Monthly Planner and
customizable Monthly Habit Tracker. No account or network connection is needed.
Notebook and Settings remain clearly labelled placeholders. Desktop/iOS launchers
are prepared; their editing destinations remain placeholders.

## Journal, planner and habits

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
signing keys; retain the same signing key for in-place updates. Backup/export is
not implemented in this phase. See [validation](docs/validation.md)
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
| shared/data | Room schema 3, explicit migrations, codec and local repositories |
| shared/ui | Shared adaptive navigation, paper colors and typography |

Apple targets are disabled by default on every host. On macOS with Xcode 26.4 use
`./gradlew -PenableIos=true :shared:ui:linkDebugFrameworkIosSimulatorArm64` and open
apps/ios/MyJournal.xcodeproj. iOS editing/viewing is outside Phase 2. Android SDK
configuration is still required when configuring this full Gradle project.

See [architecture](docs/architecture.md), [handwriting format](docs/handwriting-format.md),
[versions](docs/versions.md), [roadmap](docs/roadmap.md) and [development rules](AGENTS.md).
