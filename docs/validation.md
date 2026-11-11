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

## Phase 4 branch validation

Based on main ee6f07e, whose [signed build](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42015276624)
passed. The Phase 4 branch's full local JVM, Android debug APK, Android
instrumentation APK, lint and desktop classes command passed with JDK 21 and the
existing SDK. JUnit reports show 78 tests with zero failures, errors or skips.
Lint reported 14 warnings and zero errors. The older Google authorization library
is intentional because the newer one raises the minimum Android API.

The JVM suite exercises two independent Room databases, canonical operation
serialization, lost acknowledgments, restart before upload, retry, divergent
edits, explicit conflict resolution and delete versus edit. It also checks
atomic rollback when the task insert fails, schema 4 and 5 backup restoration,
schema 6 task/tombstone round trips, authenticated backup tampering and local
reminder scheduling/counts. Current migration tests start from older exported
schemas and preserve existing editable vectors through migration 5→6.

Local instrumentation was assembled. Hosted emulator execution and signed APK
results are recorded in the Phase 4 PR. The authenticated Google account smoke
test and physical Xiaomi alarm delivery, permission, time-zone/reboot and in-place
upgrade checks remain pending. Live synchronization is disabled. See
[Drive setup](drive-backup.md) and [protocol and scope](phase-4-sync.md).

## Phase 5 server preparation

The branch starts at 2c47988, the merged Phase 4 main commit with a passing
[signed workflow](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42571316714).
Local validation passed with JDK 21 and Android SDK 37. The complete suite ran
85 JVM tests with zero failures, errors or skips. Android debug and instrumentation
APKs and legacy desktop classes built. Lint had 14 warnings and zero errors.
OpenAPI 3.1 validation and four portable JSON fixtures passed.

Tests exercise immutable retries, lost receipts, restart before and after
acknowledgment, two-client conflicts and tombstones, incremental pages, stale
cursors, out-of-order commits, dependency errors, transaction rollback, scope
changes and blocked restore on a bound database. The schema 6→7 test preserves
an existing task and its pending operation. Every exported schema-6 table remains
unchanged. Existing tests preserve old schema 4/5/6 backups and ink. The new
independent Phase 4 encrypted fixture is decrypted, validated and restored to Room
with its original vectors and archive state.

Hosted emulator results and the signed 0.6.0 APK are tracked in the Phase 5 PR.
Production server authentication, Google Drive smoke testing and physical Xiaomi
checks remain separate from fake-transport and emulator coverage.

## Phase 6A server foundation

Based on the merged Phase 5 main commit b4128f9 with a passing
[Android workflow](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42600534677).
The isolated PostgreSQL 17.11 suite ran six Ktor HTTP integration tests with
zero failures, errors or skips. Both Flyway migrations ran against a clean
database. The suite covers concurrent commits, restart and retry, a fault after
operation insertion, contiguous cursors, stale epochs, conflict resolution,
delete versus edit, scoped workout reads, request limits, login throttling,
session expiry/revocation, Origin and CSRF checks. Four recorded HTTP responses
validated against OpenAPI alongside the four portable fixtures.

The packaged Netty distribution passed localhost checks for health, readiness
and unauthorized access. This is a disposable environment check. No production
host, public TLS exposure, PWA, Android server transport, workout mutations or
cloud restore drill was tested in this milestone.

## Phase 6B browser app

Based on merged Phase 6A main commit 2a2ade0. Local type checking, lint and the
Vite production build passed. Four Node tests covered the shared wire fixtures,
outbox persistence, receipt checks, scope binding, transactional replay and
delete/edit conflicts. The production npm dependency audit reported zero
vulnerabilities at verification time.

Three Playwright Chromium tests ran against the packaged Ktor server, disposable
PostgreSQL and a localhost HTTPS proxy. They exercised an iPhone 12 viewport,
offline startup and CRUD, explicit service worker update and reload with pending
edits, two independent browser stores, conflict resolution, lost receipts and
session loss. The Playwright test confirmed that private API responses were
absent from Cache Storage. See the Phase 6B PR for hosted results.

Physical iPhone Safari installation and production HTTPS remain unverified.
Android server binding, journal viewing after migration, workout writes and
deployment/recovery belong to later milestones.

## Phase 6C Android To-do server integration

Local validation used JDK 21, Android SDK platform 37, Build Tools 36.0.0 and
the Gradle wrapper. The shared core, domain and data JVM tests passed, including the additional
first-binding safety and Room reopen test. The shared UI module has no JVM test sources. Android debug and instrumentation
APK assembly, Android lint and desktop compilation passed. Android built
with the default settings and no Apple toolchain. The web typecheck, lint and
four unit tests passed. Three existing Chromium tests passed against
disposable Ktor and PostgreSQL.

The hosted API 36 Pixel Tablet run passed 21 existing Android regression tests.
The Android/PWA fixture was skipped in that standalone run and passed in the
dedicated integration job, which reported one Android test and one Chromium
test with no failures or skips. It exercised first binding from a verified
archive, task changes in both directions, lost acknowledgment, retry and Room
reopen, a deletion conflict, recovery epoch rejection and byte-for-byte
preservation of saved ink. The harness supplies a disposable certificate for
the Android test transport, which still verifies hostnames. See the
[Android run](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42611876059),
[server run](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42611876274)
and [web run](https://github.com/mallikarjunreddyD/LifeApp/actions/runs/42611876096).

Physical Xiaomi Pad 7 validation remains open. Use an independent local
backup and verify an in-place signed update. Check that old journals, ink,
notebooks, planner, habits, reminders, exports and Drive backup still work.
Check Android first binding through the system file picker, then verify To-do
sync with a real HTTPS server, including offline edits, network return,
process restart, expiry, conflicts and a recovery epoch change. Check stylus
accuracy, rotation and large text on the tablet. Other record types stay local.

## Phase 6D local workout increment

The browser suite passed four Chromium tests, including an offline workout
started from a routine. The test recorded 105.5 LB, eight repetitions and 45
seconds of rest, completed the workout, reloaded offline and read the same
values. Browser TypeScript checking, lint and the existing IndexedDB tests
passed. The shared codec uses the Phase 5 workout record fields on the server
read path and in Android backup data.

Pending acceptance includes Android workout UI on a tablet, browser backup
and recovery, and cross-device workout mutations,
outbox replay and conflict handling. The existing To-do sync does not carry
workout records.

## Phase 6E deployment and recovery

Local Docker built the API and web images with pinned runtime bases. The
isolated Compose stack exposed only the gateway on loopback. Native login
and logout, browser Origin and CSRF enforcement, PWA manifest and worker
assets, and PostgreSQL persistence across forced container recreation passed.
The recovery drill encrypted a consistent PostgreSQL dump with age, restored
it into a separate clean Compose project, checked the checkpoint manifest,
rotated the epoch, and verified readiness and the restored task. The API
rejected a pull using the old epoch.

The VM, Tailscale Serve and Funnel, GHCR publication, production rollout,
real Drive OAuth upload, live retention, and rollback to a previously
published image pair remain unverified. The VM's existing routes were left
untouched. This milestone does not extend which records synchronize.

## Owner authenticator sign-in

Local PostgreSQL HTTP tests covered the RFC TOTP vector, owner rename,
preserved dataset and epoch, existing session revocation, password-only
rejection after enrollment, authenticator replay rejection, one-time recovery
codes and recovery-code rotation. The legacy session insert path was rejected
by the database after enrollment. Android JVM checks, debug and instrumentation
APK assembly, and lint passed. Browser typecheck, lint, unit tests and four
Chromium regressions passed.

Production enrollment, signed APK installation on the Xiaomi Pad 7, private
HTTPS sign-in with the enrolled owner, and public access remain pending.

## Phase 7E read-only journal preview

Local JDK 21 and disposable PostgreSQL passed nine Ktor tests and packaged
server health, readiness and authorization checks. The journal coverage
checks scoped entry and stroke discovery, cursor order, cross-account
isolation, stale-epoch rejection and byte-for-byte download. The OpenAPI
document and existing contract fixtures passed validation.

Node 22 passed TypeScript checking, lint, four unit tests and the production
PWA build. All five Chromium tests passed against a disposable HTTPS
Ktor/PostgreSQL stack. The journal test committed an LJPT fixture, checked a
pixel on the rendered path, verified that the service worker did not cache
journal responses, and confirmed the preview was removed on disconnect.

The browser test used a synthetic fixture and an iPhone-sized Chromium
viewport. Physical iPhone Safari, Xiaomi Pad 7 handwriting and Android-to-PWA
journal sync remain unverified. Android first sync, editor replay and conflict
review, and Planner, Habits and Notebook synchronization remain pending.

## Phase 7E Android journal inventory

The inventory unit tests cover same-date overlap, reused entry and page
identities, archived-page counts and duplicate server records. Web typecheck,
lint, four web unit tests and all five Chromium tests passed against the
disposable stack. The Android/PWA bridge fixture now creates a server journal
entry on the same date as an Android backup entry. It checks overlap,
stale-epoch rejection and preservation of the local journal during To-do
replay. Physical device acceptance still requires selecting and verifying a
local backup through the Android system picker, reviewing the inventory and
confirming that local journal content remains intact.

## Phase 7E record archive versions

Ten tests passed against disposable Ktor and PostgreSQL. The archive
tests cover all four record kinds, staged visibility, invalid checksums,
idempotent retries, same-date variants, explicit resolution, ownership,
stale epochs, immutable stroke rejection and byte-for-byte archive
downloads. The OpenAPI contract and recorded Ktor responses passed.

Web typecheck, lint, four unit tests and the PWA build passed. All five
Chromium tests passed against a disposable HTTPS stack. The record
preview test checks rendered LJPT pixels in Journal, Planner and
Notebook and verifies that the previews disappear offline. These tests
use synthetic data. They leave Android editor upload and replay,
per-checkbox habit synchronization and physical-device acceptance open.

## Phase 7E first record transfer

The PostgreSQL suite verifies that a first upload cannot replace an existing
date, month or notebook key, including when a competing record is committed
after review. An acknowledged upload can still be retried with the same
version and archive. OpenAPI validation, web typecheck, lint, four web unit
tests and five Chromium tests passed locally.

The Room replay test exercises all four record kinds and checks that an
incoming duplicate causes the whole add-only transaction to be rejected.
The Android/PWA emulator bridge includes a queued notebook upload, retry
after reopening the queue, exact archive download into a separate Room
database, and browser vector rendering. Physical device acceptance and
continuous editor synchronization remain open.
