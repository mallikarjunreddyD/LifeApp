# Validation — Phase 1

Reviewed main 3eb4108 (Phase 0.1 CI fix merged) before creating phase-1-daily-journal.
All dates below are 2026-11-03. The working Phase 0 versions and module boundaries
were retained; the only new production libraries are stable Ink 1.0.0,
kotlinx-datetime 0.8.0 and Android lifecycle components (see versions.md).

## Local Android/JVM checks

Linux/WSL2 amd64, Temurin JDK 21.0.13, Android SDK platform 37.0 / Build Tools
36.0.0, pinned Gradle wrapper 9.7.0. Tool installations/caches are outside the repo;
no local.properties, SDK installation, credentials or build outputs are committed.

```sh
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest \
  :shared:ui:jvmTest :apps:android:assembleDebug :apps:android:lintDebug \
  :apps:desktop:classes :apps:android:assembleDebugAndroidTest
```

**BUILD SUCCESSFUL in 2m 3s; 178 tasks, 64 executed and 114 up-to-date.**
The final suite has **26 JVM tests, 0 failures and 0 errors**:

- RecordMetadataTest[jvm]: 3 passed.
- JournalDomainTest[jvm]: 7 passed.
- JournalEditorTest[jvm]: 6 passed.
- MetadataMappingTest[jvm]: 2 passed.
- FoundationDatabaseTest[jvm]: 1 passed.
- JournalRepositoryTest[jvm]: 5 passed.
- StrokePointCodecTest[jvm]: 2 passed.

Domain/UI feature-independent test infrastructure remains available; shared/ui has
no separate test cases. The new 13 domain/editor tests and seven new data tests
exercise real invariants, vectors, failed-write retry, identity/order, rollback,
file-database reopen and the schema 1→2 migration, in addition to the six existing
foundation tests. Android lint passes; non-blocking library/Gradle version notices
and Room expect/actual language-feature warnings remain. Native debug libraries may
be packaged without stripping. No failed test is represented as passed.

An early migration test exposed a Room initialization/invalidation opening stall
when starting legacy metadata observation before migration. The repository now
finishes initial read/migration before starting invalidation observation. The test
uses the actual schema 1 SQL/hash, opens through journal observation and verifies
that old metadata and new editable journal data both survive. No destructive
fallback or dependency downgrade was used.

## Android input/UI execution

The Android instrumentation APK compiles. Tests include native synthesized
finger/stylus/eraser events, pressure/highlighter properties, cancellations,
logical-coordinate capture, erase commit/cancel and Compose date/tool/page
navigation, system display rotation and Activity recreation. These test methods
are not hardware stylus validation.

No physical device is connected locally. A temporary API 36 x86_64 emulator was
provisioned, but software emulation did not complete boot in this environment;
the current user lacks read/write permission on /dev/kvm. It was stopped without
changing host security settings. No local connectedAndroidTest success is claimed.
Hosted [run 41595553392](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41595553392)
on implementation commit f912f37 passed both jobs. Downloaded JUnit reports confirm
**26 JVM tests and 5 Android instrumentation tests, all with zero failures/errors/skips**.
The original CI verification/APK/report job is preserved and also compiles the
instrumentation APK. Its first passing hosted artifacts are:

- [Debug APK](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41595553392/artifacts/13212606140)
- [JVM/lint reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41595553392/artifacts/13212795716)
- [Android input/UI reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41595553392/artifacts/13211693464)

A focused test follow-up now injects stylus events through the actual screen's
native canvas into its autosave pipeline, and uses system display rotation rather
than an app orientation request that Android 16 may ignore on large screens.
That strengthened instrumentation APK compiles locally (BUILD SUCCESSFUL in 16s);
its hosted execution is pending the follow-up push. Production source is unchanged.

## Debug APK

`apps/android/build/outputs/apk/debug/android-debug.apk` — 22372553 bytes.

SHA-256: `278ea2ccadd86641591f8a32ee1b9602463f9841a5b76349d620a9b83481f6bb`.


## Remaining device/platform validation

All physical Xiaomi Pad 7 checks in stylus-testing.md remain pending: real pen
latency, small handwriting, pressure calibration, palm/hover rejection, edges,
large multi-page performance, portrait/landscape, offline use and force-stop after
Saved. Saved vectors survive database close/reopen in automated tests; that is not
a claim that the physical-tablet acceptance scenario was executed.

No iOS build or desktop GUI session was run. Desktop compilation passes. Apple
targets remain opt-in behind -PenableIos=true; the Linux Android/JVM build does not
need Xcode. iOS requires macOS/Xcode 26.4 and is outside Phase 1 editing scope.

Undo history is session-only. Uncommitted/in-progress work survives ordinary
Activity changes in application memory, but can be lost if Android kills the
process before commit. The app shows pending/error status until writes succeed.
Other product sections remain unfinished placeholders; Phase 2 has not begun.

## Earlier CI foundation evidence

Phase 0 had six passing JVM tests, Android assembly/lint and desktop compilation.
Phase 0.1 corrected setup-android@v3's obsolete tools package default after
[failed run 41588620771](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41588620771).
[Corrected run 41589340890](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41589340890)
and PR #1 checks passed and uploaded APK/test artifacts. PR #1 was subsequently
merged by the user; Phase 1 does not undo that supported SDK provisioning setup.
