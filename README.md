# My Journal

Phase 0 foundation for an offline personal journal, primarily on Xiaomi Pad 7.
The application currently provides five navigable placeholder screens only. It does
not create, edit, save, synchronize or back up journal content. No account is needed.

## Setup

Use JDK 21 and a current stable Android Studio supporting AGP 9.3. Install Android
SDK platform 37 and Build Tools 36.0.0. Set `ANDROID_HOME` or put
`sdk.dir=/absolute/path/to/Android/Sdk` in an untracked `local.properties`.
On Windows use forward slashes for that path and `gradlew.bat` for commands.

```sh
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest :shared:ui:jvmTest
./gradlew :apps:android:assembleDebug :apps:android:lintDebug
./gradlew :apps:desktop:classes
./gradlew :apps:desktop:run
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
```

Gradle downloads require access to services.gradle.org, its distribution redirects,
Google Maven, Maven Central and the Gradle Plugin Portal. The wrapper pins and
verifies the distribution SHA-256. Initial builds need internet; the app does not.
See [validation](docs/validation.md) for passing Android/JVM build and test results
and the remaining iOS/device checks.

## Modules

| Module | Responsibility |
| --- | --- |
| `apps/android` | Primary launcher, Android platform integration |
| `apps/desktop` | JVM launcher, future Windows companion |
| `apps/ios` | Prepared SwiftUI host and Xcode project |
| `shared/core` | UUID identity, UTC timestamps, schema/revision invariants |
| `shared/domain` | Platform-independent record foundations and Flow repositories |
| `shared/data` | Room schema and explicit metadata conversions |
| `shared/ui` | Paper palette, typography and adaptive navigation shell |

Room is compiled as part of the Android app but no database is opened by the
placeholder UI. Repository implementations and feature content belong to later
phases. Navigation uses local saved selection, with a rail at widths >=720dp and
a bottom bar otherwise. Layout respects safe drawing insets and scrolls for large text.

## iOS preparation

Apple targets are disabled by default on every host, including macOS. Windows and
Linux never need Xcode to develop Android. On a Mac install Xcode 26.4, select it
with `xcode-select`, then build with `-PenableIos=true`. Open
`apps/ios/MyJournal.xcodeproj`, select a signing team for a physical device, and run.
The Xcode build phase builds and embeds the shared framework. Android SDK setup is
still needed for the full Gradle project configuration. iOS has not been built here.

See [architecture](docs/architecture.md), [versions](docs/versions.md),
[roadmap](docs/roadmap.md) and [agent rules](AGENTS.md).
