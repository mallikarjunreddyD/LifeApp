# Validation — Phase 2

Phase 1 PR #2 was merged. Phase 2 branches from updated main ac38145 on
phase-2-monthly-planner-habits; main is not modified. Validation date: 2026-11-03.
Kotlin/Compose/AGP/Gradle/Room/SQLite/Ink versions remain unchanged from Phase 1.
No production dependency or Apple toolchain requirement was added.

## Local Android/JVM suite

Linux/WSL2 amd64, Temurin JDK21.0.13, SDK platform37.0 / Build Tools36.0.0,
pinned Gradle9.7.0. Tooling/caches live outside the repository.

```sh
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest \
  :shared:ui:jvmTest :apps:android:assembleDebug :apps:android:lintDebug \
  :apps:desktop:classes :apps:android:assembleDebugAndroidTest
```

**BUILD SUCCESSFUL in 8s; 178 tasks (21 executed, 157 up-to-date).**
JUnit XML confirms **46 JVM tests, zero failures/errors**. All 26 Phase 1 tests
remain, with 20 new tests:

| Suite | Passed |
| --- | ---: |
| RecordMetadataTest | 3 |
| JournalDomainTest | 7 |
| JournalEditorTest | 6 |
| JournalMemoryTest | 4 |
| MonthDomainTest | 6 |
| MonthEditorTest | 4 |
| MetadataMappingTest | 2 |
| FoundationDatabaseTest | 1 |
| JournalRepositoryTest | 5 |
| MonthRepositoryTest | 6 |
| StrokePointCodecTest | 2 |

Tests cover lazy selected-page loading, saved LRU eviction/reload/selection,
failed-write and native-bound protection, leap/month/year/Monday/5–6 row geometry,
coordinate transforms, explicit copy/independent definitions, stable history after
rename/reorder/archive, rapid toggles, retry across month navigation, transactional
identity validation, planner vectors after real-file reopening and erasure/restore.
Existing schema1 migration now executes the complete 1→2→3 chain. A schema2 fixture
preserves journal UUIDs, timestamps, revisions and exact vector bytes through 2→3.
Schemas1/2 remain unchanged; schema3 is exported. No destructive fallback.

Android debug APK, lint, desktop classes and instrumentation APK compile successfully.
shared/ui has test infrastructure but no separate test cases. Non-blocking test-rule
deprecation, Room expect/actual and native debug stripping notices remain.

## Android instrumentation execution

Eight instrumented tests compile: the five retained Phase 1 tests plus three new
MonthUiTest scenarios. They synthesize native stylus input through the real shared
canvas and test planner cell ink/Undo/Redo/month isolation/date taps, habit touch
editing/archive/restore/copy, linked journal ink, Back/scroll restoration, rotation,
Activity recreation and reopening a database connection.

Hosted [push run 41612983421](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41612983421)
on source/test commit 41dabfb passed both verify and android-input jobs. The matching
[PR run 41612991256](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41612991256)
also passed. Downloaded JUnit reports confirm **46 JVM tests and 8 Android tests,
zero failures/errors/skips**. Lint passed with five non-blocking warnings (three
version notices and two UseKtx suggestions). The existing CI workflow is unchanged.

Validated artifacts from the passing push run:

- [Debug APK](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41612983421/artifacts/13220635146)
- [JVM and lint reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41612983421/artifacts/13218989602)
- [Android input/UI reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41612983421/artifacts/13219438806)

All five retained Phase1 Android cases passed. New tests passed for planner cell
strokes/Undo/Redo/month separation/date-tap/Back/rotation/recreation/database reopen,
four-habit touch editing/rename/reorder/archive/restore/definitions-only copy/month
isolation, and linked journal ink with vertical/horizontal ledger-position restoration.
The first emulator run exposed only an assertion comparing optimistic timestamps to
authoritative transaction timestamps; the assertion now checks durable identity and
content. Production source did not need changing for that failure.

No physical Xiaomi test has been performed. Local connected tests are unavailable:
no device is attached and the current user cannot use /dev/kvm; the prior software
emulator did not finish booting and was stopped. Hosted tests execute on the KVM
API36 Pixel Tablet emulator. Synthetic input is not physical stylus validation.

The local debug APK is `apps/android/build/outputs/apk/debug/android-debug.apk`,
22,682,408 bytes; SHA-256
`cb23f3231f15a3ba852ca6d4c4a5d45a24a4913f120649e3587e38fb44899838`.
Hosted APKs use the runner's debug signing key and can have different bytes. Retain
one signing key for in-place device updates; backup/export is not implemented.
No APK, SDK path, credential or build output is committed.

[PR #3](https://github.com/mallikarjunreddyD/LifeApp/pull/3) targets main and is left
open/unmerged. A documentation-only follow-up records this evidence; source and test
implementation are identical to the successful commit above.

## Remaining validation and limitations

All physical Xiaomi Pad7 checks in stylus-testing.md remain pending and are explicitly
deferred by agreement. Real stylus latency, palm/hover rejection, pressure calibration,
edge behavior, long-page performance, large text and tablet usability are unverified.
Synthetic emulator events are not hardware validation. The manual November12/20
planner, four-habit ledger and linked journal scenarios are listed in that checklist.

Saved content is tested across real database close/reopen. Queues remain memory-only:
unexpected process termination before commit can lose pending/in-progress work.
Ordinary Activity recreation retains the application queues; no loss-free process-kill
claim is made. Saved page-cache eviction can unload Undo/Redo while preserving stored
vectors. Pending/native-bound/in-flight work may temporarily exceed soft cache caps.

No iOS build or desktop GUI session was executed. Desktop compilation passes and
Apple targets remain opt-in. No Xcode was installed or needed for Android/JVM checks.
Notebook/Settings and later product phases remain unimplemented.
