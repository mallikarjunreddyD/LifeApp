# Validation — Phase 3

Branched from latest merged/validated main a042672 (Phase2 PR#3) on
phase-3-notebook-export-backup. Validation date: 2026-11-03. Main is not modified.
Existing Kotlin/Compose/AGP/Gradle/Room/SQLite/Ink versions are preserved; the only
new production library is stable serialization-json1.11.0 for portable records.

## Local checks

Linux/WSL2 amd64, Temurin JDK21.0.13, Gradle9.7.0, SDK platform37.0 / Build Tools36.0.0.
Tool installations/caches remain outside the repository; no SDK path, APK, credentials
or generated build artifacts are committed. Room schema exports are intentional source.

```sh
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest \
  :shared:ui:jvmTest :apps:android:assembleDebug :apps:android:lintDebug \
  :apps:desktop:classes :apps:android:assembleDebugAndroidTest
```

Full suite: **BUILD SUCCESSFUL in 1m14s; 178 tasks (37 executed,141 up-to-date)**.
JUnit XML confirms **64 JVM tests, zero failures/errors/skips**. All 46 Phase1/2 tests
are retained, plus 18 Phase3 cases:

| New suite | Passed |
| --- | ---: |
| NotebookEditorTest | 4 |
| CommittedWritesTest | 3 |
| NotebookRepositoryTest | 1 |
| BackupRoundTripTest | 4 |
| BackupCorruptionTest | 5 |
| SchemaThreeMigrationTest | 1 |

Coverage includes notebook create/rename/page order/templates/archive/restore, exact
Undo/Redo, lazy bounded caches and failed/native-bound work, save barriers across
all four modules and timeout/error refusal, real-file notebook reopening, all-module
backup validation/restore/reopen with exact IDs/timestamps/revisions/point bytes,
editable restored ink, JSON/native-vector round trips, and failed/interrupted write
rollback after earlier replacement mutations. Invalid graphs never change current data.

Corruption cases include malformed manifests/JSON, missing/extra entries, checksums,
unsupported schema/point versions, duplicate IDs/dates/completions, orphan ownership,
corrupt LJPT, truncated ZIP, duplicate paths, traversal/absolute/backslash paths,
expansion limits and deep JSON. Schema3 is built from its committed SQL/hash with all
prior modules populated, then migrated to4 and compared exactly. Older schema1/2
migration tests now run through the full chain to4. Schemas1/2/3 remain unchanged.

The tests found and fixed transaction-local temporary page-order validation and
snapshot initialization before old-schema migration. No destructive fallback or
flattening was used. Notebook background changes now explicitly invalidate native
paper rendering while preserving all vectors; per-book remembered page selection
falls back correctly when a different book's saved page ID is supplied.

Android debug build, lint, desktop classes and instrumentation APK assembly pass.
Lint/Gradle/test-rule deprecation and Room expect/actual notices remain non-blocking.
No desktop GUI session or iOS/Xcode build was run; Apple targets remain opt-in and
Android/JVM checks require no Xcode. shared/ui has no separate test cases.

## Android execution

Hosted [run41628469399](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41628469399)
on implementation commit16bd0bc passed both verify and android-input jobs. Downloaded
JUnit XML confirms **64 JVM and13 Android tests, zero failures/errors/skips**.
All eight Phase1/2 Android regressions are retained, plus NotebookUiTest(1),
PdfExportTest(2) and BackupOperationsTest(2). The13 Android cases completed in108.173s
on the API36 Pixel Tablet KVM emulator. Lint has no errors; seven non-blocking warnings
are version notices/UseKtx suggestions. The existing CI workflow is unchanged.

Validated artifacts:

- [Debug APK](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41628469399/artifacts/13226348547)
- [JVM/lint reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41628469399/artifacts/13227002819)
- [Android input/UI/PDF/backup reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41628469399/artifacts/13226589255)

The local debug APK is `apps/android/build/outputs/apk/debug/android-debug.apk`,
23,369,152 bytes; SHA-256
`0f6944f0902e1849fe6fadcd3a03187bffb005bd48dccc4f7a1db48aa2966d96`.
Hosted APK bytes/signing keys can differ; retain a consistent key for in-place updates.

A documentation-only follow-up records these results; source/tests remain identical
to the passing implementation commit above. The correction branch targets main and
is not merged automatically.

New tests inject native stylus events in Notebook, change templates/order/archive,
recreate the Activity, rasterize PDFs with PdfRenderer for visible pen/highlighter
colour/alpha/geometry/background/order assertions, exercise the CreateDocument result
contract, verify selected-file backup output, clean/replacement restore, mandatory
verified recovery, cache rebuild and corrupt input refusal against isolated data.
The file-picker contract test intercepts its result with a test file URI; actual
third-party provider interaction/durability remains a manual check.

No local device is connected and /dev/kvm is unavailable to this user. The previous
software emulator did not complete boot and was stopped; no local connected-test
success is claimed. Physical Xiaomi testing is explicitly deferred, not a Phase3 blocker.

## Limits and remaining validation

Read backup-format.md, backup-restore.md and export-formats.md for exact format,
bounds, transaction and recovery behavior. Full snapshots allow50,000 records and
2,000,000 points; ZIP compressed/expanded total64MiB; record/manifest JSON8MiB each.
Oversized operations fail rather than silently omit content. PDF is ≤500 pages/128MiB.
Backups are unencrypted and checksums do not authenticate their author. A failed
provider write can leave a partial destination; success requires reread verification.

Pending/in-progress edits remain memory-only until committed and can be lost on
unexpected process death. Undo/Redo is session-only and can be unloaded with saved
cache entries. Restored committed content is editable; no guarantee is made that
unfinished provider writes survive power loss. PDF pressure/brush smoothing is a
controlled approximation of native Ink, not a pixel-identical mesh rendering.

All physical Xiaomi latency/palm/pressure/edge/large-page/orientation/provider checks
remain pending in stylus-testing.md. No hardware validation or cloud/account/sync,
iOS editing, Windows editing, handwriting recognition or Phase4 work is claimed.
