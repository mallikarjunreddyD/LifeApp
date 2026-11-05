# Validation — Phase 3.1

2026-11-05. Branch fix/phase-3-1-tablet-ink-layout starts at latest merged main2c82a17
(Phase3 PR#4). Application ID and Kotlin/Compose/AGP/Gradle/Ink versions are preserved.
Main is not modified. Physical Xiaomi tests reported sizing/offset issues; corrective
hardware acceptance is still pending, not claimed from synthetic events.

## Confirmed software evidence

Bytecode from the original dry/wet anonymous View classes calls child View.getMatrix()
at both Canvas.concat and CanvasStrokeRenderer.draw. That inherited identity property
shadowed the outer matrix, whereas input inverted the correct page transform. Bytecode
from the repaired classes calls JournalCanvas.access$getPageToViewMatrix instead.
The exact pinned Ink1.0.0 source JAR confirms the renderer argument does not itself
apply a Canvas transformation. See tablet-ink-alignment.md for pipeline/examples.
Stored point coordinates/LJPT1 and calendar geometry version1 are not rewritten.

## Local Android/JVM checks

Linux/WSL2, Temurin JDK21.0.13, SDK platform37.0/Build Tools36.0.0 and Gradle9.7.0.
Tools/caches live outside the repository. No SDK paths, keys, passwords or APK/build
outputs are committed. The small frozen Phase3 ZIP/JSON files are synthetic compatibility
fixtures intentionally kept under jvmTest/resources, not application backups/artifacts.

```sh
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest \
  :shared:ui:jvmTest :apps:android:assembleDebug :apps:android:lintDebug \
  :apps:desktop:classes :apps:android:assembleDebugAndroidTest
```

Full local suite: **BUILD SUCCESSFUL in2m15s;179 tasks(34 executed,145 up-to-date)**.
71 JVM tests passed, zero failures/errors/skips. All64 Phase1–3 tests remain plus:

- JournalArchiveTest:4 — last-page constraint, exact archive/restore/order/identity,
  failed writes/late bound gestures/navigation/retry and repeated deletion/restoration.
- JournalArchiveRepositoryTest:2 — transactional/idempotent archive, late writes,
  reopening, exact vectors, full backup/restore/PDF preservation, and authentic old
  Phase3 ZIP/JSON reading/restoration into schema5.
- SchemaFourMigrationTest:1 — committed schema4 SQL with every prior module populated,
  first journal read, default-active pages and exact metadata/vector preservation.

All earlier migration tests retain their original data assertions and now run through
1→2→3→4→5. Schema exports1–4 remain unchanged;5 is exported. Schema4 fixture construction
was batched in one SQL transaction after tracing a test timeout to its per-row file
writes, before migration was reached. No production initialization workaround or test
timeout increase was used for that fixture issue.

Android debug APK, lint, desktop classes and instrumentation APK compile successfully.
No iOS/Xcode or desktop GUI session was executed. Apple targets remain opt-in; no Xcode
was required for Android/JVM. Local connected testing remains unavailable because
this user lacks /dev/kvm access and no device is attached. Hosted execution is pending.

## Android rendering/UI coverage

17 Android instrumentation tests compile: all13 existing cases plus three
CanvasRenderAlignmentTest methods and one JournalPageArchiveUiTest. Pixel tests use
hardware-window PixelCopy, not software drawing substitutes or only serialized points.
They compare physical contacts with wet/dry/Room-reloaded red-pixel centroids within5
screen pixels at1×/1.5×/2×, logical page corners/center, translated viewports, diagonal
strokes, actual system display rotation, eraser hits, notebook templates/highlighter
and calendar cell clipping/date taps. Nonzero native parent offsets exercise window
coordinate handling. The UI test verifies actual Compose/native child bounds and
right-edge Fit Width paper pixels, active gesture handoff, confirmation/cancel,
last-page disabling, selection/counts, archived vectors and restoration after recreation.
Compilation is not represented as executed Android success; hosted results will be
recorded after the emulator workflow finishes. Existing PDF pixel, backup/restore,
habit/navigation/stylus tests remain enabled in the same CI job.

## Signing and device limits

Optional LIFEAPP_SIGNING_* environment configuration supports the original external
keystore without committed keys/passwords. SigningReport compares the builder's default
certificate with explicitly configured use of that same existing key. The installed
Xiaomi certificate is unavailable here; compatibility is not inferred from an APK build.
Follow safe-android-upgrade.md: create/verify/copy a Phase3 backup first, compare installed
and candidate certificate SHA-256, use the original private key and adb install -r.
Do not uninstall/clear data to bypass a mismatch. A discarded CI runner key cannot be
recovered from the artifact's public APK. CI debug APKs are not guaranteed matching keys.

Repeat the Phase3.1 checklist in stylus-testing.md on Xiaomi Pad7 for real tip/contact
alignment, pressure/palm/hover/latency, both orientations, fit modes, deletion/recovery
and original-backup restoration after a matching-key update. None is claimed completed.

Unchanged limits: pending edits/Undo are memory-only until committed/session history
is unloaded; full snapshots50,000 records/2,000,000 points, ZIP64MiB compressed/expanded,
JSON8MiB each, PDF500 pages/128MiB. Backups are unencrypted/checksummed, PDF brush fidelity
is approximate, actual provider durability needs device testing. No Phase4 work.
