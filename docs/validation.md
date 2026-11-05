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
this user lacks /dev/kvm access and no device is attached. The full final implementation passes both hosted jobs; execution evidence is below.

## Android rendering/UI coverage

18 Android instrumentation tests compile: all13 existing cases plus three
CanvasRenderAlignmentTest methods, one JournalPageArchiveUiTest and one
EditorHeaderLayoutTest. Pixel tests use
hardware-window PixelCopy, not software drawing substitutes or only serialized points.
They compare physical contacts with wet/dry/Room-reloaded red-pixel centroids within5
screen pixels at1×/1.5×/2×, logical page corners/center, translated viewports, diagonal
strokes, actual system display rotation, eraser hits, notebook templates/highlighter
and calendar cell clipping/date taps. Nonzero native parent offsets exercise window
coordinate handling. EditorHeaderLayoutTest prevents autosave status from moving/resizing the editor.
The UI test verifies actual Compose/native child bounds and
right-edge Fit Width paper pixels, active gesture handoff, confirmation/cancel,
last-page disabling, selection/counts, archived vectors and restoration after recreation.
Hosted [run41977721629](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41977721629)
on implementation fbf2318 passed both jobs. Downloaded reports confirm71 JVM and17
Android cases with zero failures/errors/skips; the Android cases completed in114.766s.
The strengthened saved-vector/rotated-size test follow-up dd1f746 also passed both jobs
in [run41978339799](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41978339799).
The final implementation/test follow-up8c88139 passed both jobs in
[run41980697532](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41980697532).
Downloaded final JUnit XML confirms **71 JVM and18 Android tests, zero failures,
errors or skips**. EditorHeaderLayoutTest verifies stable writing bounds across save
states. JournalPageArchiveUiTest additionally injects screen-coordinate stylus events
through the real Window/Compose/AndroidView path, compares resulting ink centroids
within5 pixels, and checks the native origin is unchanged after autosave. No hardware
tip calibration is claimed from these synthetic tests.

Validated final-source artifacts:

- [Debug APK](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41980697532/artifacts/13393219851)
- [JVM/lint reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41980697532/artifacts/13393369460)
- [Android rendering/UI reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/41980697532/artifacts/13394445433)

After the autosave header change, local targeted Android build/lint/desktop/instrument
assembly also passed in12s(165 tasks;30 executed,135 up-to-date), and final screen-input
instrumentation assembly passed in1s. Full hosted verification includes all current
source/tests. The local debug APK is23,436,279 bytes, SHA-256
`93fcfa13fdc1c9f7e628a68287f8fdb522aa71c51b733a2ed9497cef3fcc0e8c`;
its path is apps/android/build/outputs/apk/debug/android-debug.apk. CI signatures/bytes
can differ. A documentation-only follow-up records these results; implementation and
tests are unchanged from passing8c88139. Existing PDF pixel, backup/restore,
habit/navigation/stylus tests remain enabled in the same CI job.

## Signing and device limits

Optional LIFEAPP_SIGNING_* environment configuration supports the original external
keystore without committed keys/passwords. SigningReport compares the builder's default
certificate with explicitly configured use of that same existing key. The installed
Xiaomi certificate is unavailable here; compatibility is not inferred from an APK build.
Read-only apksigner checks confirm the previously linked Phase3 CI artifact's signer
is different from the first correction CI artifact's signer (public digests are recorded
in safe-android-upgrade.md). Those two APKs cannot update one another in place.
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


## Persistent signing correction (after merged PR5)

Branch fix/persistent-apk-signing starts at main2a0e6d0. VersionCode6/name0.4.2 retain
application ID/data schema5 and the Phase3 compatibility readers. A dedicated key is
stored in encrypted GitHub Actions secrets and a protected local recovery directory
outside Git; only its public certificate pin is committed. No signing material is
included in APK/report artifacts.

Local missing-secret and unexpected-signer tests refuse publication. Preparing the
existing protected key, full signed Android/JVM verification and actual apksigner
certificate-pin/package checks pass;71 JVM tests have zero failures/errors/skips.
Android debug/lint, desktop classes and instrumentation assembly pass with the
persistent key. Workflow YAML/event boundaries and Python syntax are validated.
Hosted [run42006942165](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42006942165)
passed all three jobs at implementation commit c54db25. Downloaded reports confirm
71 JVM and18 API36 tablet-emulator tests with zero failures/errors/skips; Android
lint passed with seven warnings and no errors. The downloaded installable APK passes
independent apksigner verification, matches the committed persistent certificate and
local signed build, and reports com.myjournal.android, versionCode6/versionName0.4.2.
This documentation-only follow-up changes no implementation or tests.

- [Persistent-key installable APK](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42006942165/artifacts/13405024437)
- [JVM/lint reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42006942165/artifacts/13404599776)
- [Android instrumentation reports](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42006942165/artifacts/13405043760)

The public certificate SHA-256 is
`27688897e503889b893458c3dfa53a05268e9255a32f612ec702d9f6900f5cdd`.
Further independent-run certificate comparisons are recorded in
[PR6](https://github.com/mallikarjunreddyD/LifeApp/pull/6).
No physical Xiaomi installation/restore or stylus validation is claimed.

The separate my-journal-installable artifact is uploaded only after the signed APK
matches config/android-signing-certificate.sha256. CI-only APKs are renamed
my-journal-ci-debug-ephemeral. The signed job does not run for PRs; it waits for both
normal verification jobs and accepts only main/the owner-requested bootstrap branch.
See persistent-apk-signing.md for secret handling, recovery and the unchanged old-CI
certificate constraint. No tablet data is uninstalled or modified by this task.
