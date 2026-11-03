# LifeApp — My Journal

An offline-first personal journal, primarily for Xiaomi Pad 7 and a compatible stylus.
Phase 1 implements the Android Daily Journal with editable vector handwriting and
durable local storage. No account or network connection is needed. Monthly Planner,
Habit Tracker, Notebook and Settings remain explicitly unfinished placeholders.
Desktop/iOS launchers are prepared; their Daily screen remains a placeholder.

## Android journal

Today's local date opens by default. Use previous/next day, Today or the date picker.
Stylus input writes; fingers pan and pinch to zoom. Choose pen, highlighter or
whole-stroke eraser, adjust width and select black/blue/red/green. Undo/redo works for
added and erased strokes. Add pages to a date and revisit earlier pages to edit them.
The fixed logical paper coordinates survive zoom/density/orientation changes.

Completed strokes save automatically to Room. “All changes saved locally” appears
only after the queued writes succeed. If storage fails, ink and queued edits remain
in memory with an error and Retry action. Wait for successful saving before force
stopping: a process kill before commit can lose pending/in-progress edits. Saved
vectors remain editable on restart; undo history is not persisted across process death.

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
the application itself uses no online services. See [validation](docs/validation.md)
for the actual checks and [stylus testing](docs/stylus-testing.md) for remaining
physical Xiaomi validation. Do not infer device behavior from compilation alone.

## Modules

| Module | Responsibility |
| --- | --- |
| apps/android | Android composition, journal UI, native stylus/Ink adapter |
| apps/desktop | JVM launcher for the shared shell |
| apps/ios | Prepared SwiftUI/Xcode host |
| shared/core | UUID/UTC metadata and revision invariants |
| shared/domain | Portable journal vectors, repositories and editor state/history |
| shared/data | Room schema 2, migrations, codec and local repository |
| shared/ui | Shared adaptive navigation, paper colors and typography |

Apple targets are disabled by default on every host. On macOS with Xcode 26.4 use
`./gradlew -PenableIos=true :shared:ui:linkDebugFrameworkIosSimulatorArm64` and open
apps/ios/MyJournal.xcodeproj. iOS editing/viewing is outside Phase 1. Android SDK
configuration is still required when configuring this full Gradle project.

See [architecture](docs/architecture.md), [handwriting format](docs/handwriting-format.md),
[versions](docs/versions.md), [roadmap](docs/roadmap.md) and [development rules](AGENTS.md).
