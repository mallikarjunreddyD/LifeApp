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
