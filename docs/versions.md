# Dependency decisions

Verified against official sources on 2026-11-03. No alpha, beta or release candidate
dependencies are intentionally used. Exact versions live in `gradle/libs.versions.toml`.

| Component | Pinned version | Official evidence |
| --- | --- | --- |
| Kotlin / Compose compiler | 2.4.21 | [KMP compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) |
| Compose Multiplatform | 1.12.2 | [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) |
| AGP | 9.3.1 | [AGP releases](https://developer.android.com/build/releases/agp-9-3-0-release-notes) |
| Gradle | 9.7.0 | [Gradle releases](https://gradle.org/releases/) |
| Room | 2.8.5 | [Room releases](https://developer.android.com/jetpack/androidx/releases/room) |
| SQLite bundled | 2.7.1 | [SQLite releases](https://developer.android.com/jetpack/androidx/releases/sqlite) |
| Coroutines | 1.11.0 | [Official releases](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) |
| KSP | 2.3.13 | [Official releases](https://github.com/google/ksp/releases/tag/2.3.13) |
| Android Activity | 1.13.0 | [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity) |

The Kotlin compatibility table explicitly supports AGP through 9.3.1 and Gradle
through 9.7.0. Newer AGP patch releases and Gradle 9.8.2 exist, but fall outside
that documented combination. Compose states that its latest stable supports the
latest Kotlin; the compiler plugin uses the Kotlin version. AGP 9.3 requires
Gradle >=9.5.0 and JDK >=17; this project standardizes on JDK 21. Use the dedicated
Android KMP library plugin, with a separate Android application module and AGP's
built-in Kotlin support. Room 2.8 requires Android API >=23.

This documented combination also passed Android/JVM compilation and JVM tests in
the local integration build. iOS remains unverified. See validation.md for results.
Material 3 is pinned separately at 1.9.0, the stable artifact selected by the
Compose 1.12.2 plugin, rather than assuming it shares the Compose version.


## Phase 1 additions (verified 2026-11-03)

Existing Kotlin 2.4.21, Compose 1.12.2, AGP 9.3.1, Gradle 9.7.0, Room 2.8.5 and
SQLite 2.7.1 pins remain intact. New catalog dependencies are stable:

- [Jetpack Ink 1.0.0](https://developer.android.com/jetpack/androidx/releases/ink):
  brush, strokes and native canvas rendering only in apps/android. Stable source
  artifacts were inspected for InProgressStroke, MutableStrokeInputBatch,
  CanvasStrokeRenderer and explicit StockBrushes V1 APIs. Ink 1.1 alpha APIs and
  authoring-compose are unnecessary for this native-view adapter.
- [kotlinx-datetime 0.8.0](https://github.com/Kotlin/kotlinx-datetime/releases/tag/v0.8.0):
  portable calendar dates, leap/year arithmetic and local Today without UTC-date shifts.
- [Lifecycle 2.11.0](https://developer.android.com/jetpack/androidx/releases/lifecycle):
  Android ViewModel and SavedStateHandle; no DI framework is added.
- [AndroidX Test runner/core 1.7.0 and JUnit 1.3.0](https://developer.android.com/jetpack/androidx/releases/test).
- Compose Android UI test 1.12.1, matching the Android artifact version documented
  for Compose Multiplatform 1.12.2. Test dependencies are not production features.

MotionEvent/palm/cancellation decisions follow the official
[advanced stylus guide](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features).
The hosted emulator job follows the documented Linux/KVM setup of
[android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner).

## Phase 3 addition

Stable kotlinx.serialization-json1.11.0 is added for portable JSON tree encoding/validation,
without a serialization compiler plugin or changing pinned Kotlin/Compose/AGP/Gradle/
Room/SQLite/Ink versions. [Official setup](https://kotlinlang.org/docs/serialization-get-started.html)
and [release notes](https://github.com/Kotlin/kotlinx.serialization/blob/master/CHANGELOG.md#1110--2026-04-10)
identify this stable version (based on Kotlin2.3.20); it is compiled/tested with the
existing Kotlin2.4.21 toolchain. PDF and ZIP use platform/standard-library APIs.
