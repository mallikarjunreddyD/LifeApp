# Phase 0 validation

Executed on 2026-11-03 under Linux/WSL2 (amd64). The host initially had only a
Java 21 runtime, no javac, no Gradle installation and no configured Android SDK.
A complete Temurin JDK 21.0.13, Android command-line tools, SDK platform 37.0,
Build Tools 36.0.0 and Platform Tools 37.0.1 were provisioned in `/tmp` for checks.
These temporary tools are not project dependencies or committed files.

The first direct Java wrapper download timed out. Downloading the official Gradle
9.7.0 distribution with curl worked; its SHA-256 matched the wrapper's pinned
checksum. The checked-in wrapper then ran successfully using that distribution
in its local cache. No project version downgrade or substitute build was used.

## Verification command

```sh
./gradlew :shared:core:jvmTest :shared:domain:jvmTest \
  :shared:data:jvmTest :shared:ui:jvmTest \
  :apps:android:assembleDebug :apps:android:lintDebug :apps:desktop:classes
```

The first full run passed (147 tasks executed). The final checked-in source also
passed through the wrapper: BUILD SUCCESSFUL in 1m 9s; 146 actionable tasks,
139 executed and 7 up-to-date. After the final pre-Android-12 backup setting,
Android assembly/lint passed again in 37s (123 tasks: 18 executed, 105 up-to-date).

- Core: 3 tests passed (revision identity, invariants, overflow/backward time).
- Data mapping: 2 tests passed (nanosecond/revision round-trip, corrupt identity).
- Room JVM integration: 1 test passed (empty state, committed insert and upsert).
- Domain/UI: test infrastructure configured; no feature-specific tests yet.
- Android debug assembly: passed; APK generated.
- Android lint: passed. Version-update notices intentionally retain the documented
  Kotlin-compatible AGP/Gradle combination. The initial icon/backup suggestions
  were addressed in the final source. The generated lint report still retains a
  DataExtractionRules advisory requesting fullBackupContent; the final manifest
  explicitly sets fullBackupContent=false and allowBackup=false, alongside
  Android 12+ exclusion rules. This advisory does not fail lint.
- JVM desktop launcher and shared UI compilation: passed.
- Room KSP: Android/JVM generated successfully; version 1 schema is committed.
- Android resources, manifest and shared Xcode scheme: XML parsing passed.

## Remaining platform checks

No iOS compilation was attempted: this Linux host has no Apple toolchain. Use
macOS, Xcode 26.4 and the setup in README.md. Run:

```sh
./gradlew -PenableIos=true :shared:ui:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project apps/ios/MyJournal.xcodeproj -scheme MyJournal \
  -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

No emulator/device or desktop GUI session was used. The APK has not been launched
on Xiaomi Pad 7; portrait/landscape, large-text and touch navigation need a device
smoke test. No handwriting or persistence feature is exposed by this shell.

## Non-blocking build diagnostics

Room's documented generated constructor uses Kotlin expect/actual classes, which
still produce a compiler Beta language-feature warning despite stable dependencies.
Native debug libraries are packaged without stripping when the optional stripping
toolchain is absent. These do not prevent assembly or unit tests.

## Debug artifact

`apps/android/build/outputs/apk/debug/android-debug.apk` (16832163 bytes).

SHA-256: `0452af0d7f59cc1d036b37c1a98a178269865106f098ca0841b768297e119302`.

APK signature verification passed. The APK is a generated build output and is not
committed; GitHub Actions uploads it as a build artifact.

## Phase 0.1 — CI SDK provisioning correction

Reviewed latest main `79b2bc1` and failed [run 41588620771](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41588620771)
on 2026-11-03. Job `125759869425` failed before Gradle: setup-android@v3's
implicit `packages: tools platform-tools` invoked `sdkmanager tools`. The obsolete
`tools` package was unavailable, producing `Failed to find package 'tools'` and
exit code 1. License acceptance had succeeded; the Gradle and explicit SDK install
steps were skipped. This was an SDK action default failure, not a Kotlin/AGP,
compilation or unit-test failure.

The workflow now overrides that default with `platform-tools`,
`platforms;android-37.0` and `build-tools;36.0.0`, using the action's supported
[packages input](https://github.com/android-actions/setup-android/blob/v3/action.yml).
The action installs each package and accepts licenses; the duplicate downstream
SDK install step is removed. APK/report uploads fail if expected files are absent;
reports include HTML tests, JUnit XML and Android lint output, including after a
verification failure. Reports are skipped if verification never started.

Local verification reran the complete command above with `--rerun-tasks
--no-build-cache --no-daemon --stacktrace`: **BUILD SUCCESSFUL in 42s; all 146
actionable tasks executed**. All six tests passed, including the Room metadata
upsert integration test. Android debug assembly, Android lint and desktop classes
passed on Linux without Xcode or `enableIos` enabled. Hosted CI verification is
pending publication of this correction branch; no hosted success is claimed yet.

Additional review confirmed:

- All four shared modules keep Apple targets behind `-PenableIos=true`.
- Core/domain remain free of platform, Compose and Room imports; shared UI has no
  database access. Module dependencies and application source are unchanged.
- The shell still explicitly labels its screens as previews and says writing,
  editing and saving are unavailable; no Phase 1 functionality was added.
- JDK 21 and all Kotlin/Compose/AGP/Gradle versions are unchanged.
- No generated build outputs, local.properties, SDK installations or credentials
  are tracked. Ignore checks cover local.properties, build outputs and .gradle;
  a tracked-file scan found no private-key/token indicators. No machine-specific
  SDK configuration was added.

The iOS and Xiaomi Pad 7 device checks listed above remain outstanding. No device,
handwriting, desktop GUI or iOS runtime validation is implied by the build results.
