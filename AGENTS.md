# My Journal development rules

## Scope and boundaries

Work only on the requested phase. Android on Xiaomi Pad 7 is primary. Keep all
features offline-first, with no mandatory registration. Do not introduce backend,
cloud, synchronization or account dependencies outside authorized work. Never present
placeholder editing, saving or settings as working functionality.

Apps own launchers and platform adapters. shared/ui owns Compose and design tokens;
shared/data owns Room, conversions and local repository implementations;
shared/domain owns platform-independent records and repository contracts;
shared/core owns identity and metadata invariants. Dependencies point inward toward
core. No platform, Compose or Room imports in core/domain. No database access in UI.
Keep MotionEvent and Jetpack Ink APIs in the Android adapter. The versioned portable
handwriting format is documented in docs/handwriting-format.md; preserve old readers
and exact vectors on changes. Hardware behavior must be checked on Xiaomi Pad 7;
never label synthetic/emulator input checks as hardware validation. Avoid speculative
frameworks and abstractions. Phase 2 implements Daily Journal, Monthly Planner and Monthly Habit Tracker only.
Phase 3 adds Notebook and local export/backup/restore Settings. Phase 4 adds offline
To-do, reminders, a tested To-do operation journal and encrypted manual Drive
backup. Keep live synchronization disabled until the protocol covers safe account
partitioning, restore and existing records. Phase 5 prepares the server contract. Do not begin Phase 6 automatically.
Backup must drain every editor queue, validate a committed snapshot and verify output.
Restore must validate before writes, require recovery backup for replacement, commit
atomically and rebuild editor caches. Never merge archives or discard pending edits.
Use the same Android ink adapter for journal/planner/notebook.
Phase3.1 corrects rendering and adds recoverable journal page archival. Keep at least
one active page, preserve original IDs/order/ink, and retain schema4 backup readers.
Use an explicitly named pageToViewMatrix from child Views; inherited View.matrix
must never shadow the page transform. Pixel-test actual hardware rendering.
Never change stored vectors to compensate for a display bug. Verify certificate
compatibility for in-place updates; never require uninstall or commit signing secrets. Preserve canonical calendar
geometry versions and LJPT readers. Habits use stable IDs, monthly isolation and
boolean completions; archive historical definitions rather than deleting their data.
Keep pending/in-flight/native-bound drafts protected when trimming saved caches.
Physical Xiaomi checks are deferred and must not block Phase 2 automated validation.

## Conventions

Use Kotlin official style, four spaces, explicit names and immutable data where
possible. Use Kotlin UUIDs and UTC Instants for identity/timestamps. Preserve record
creation times and increment revisions on edits. Distinguish record schema versions
from Room database versions. Use suspend functions for writes and Flow for observable
local data. Surface storage failures; never silently drop user content. Do not use
destructive Room migrations. Export/review generated Room schemas on schema changes.
Use catalog versions, stable official dependencies and Gradle Kotlin DSL only.
Keep Apple targets opt-in; never require Xcode for Android development on Windows.

## Commands and testing

Use JDK 21 and Android SDK platform 37 / Build Tools 36.0.0. Windows commands use
`gradlew.bat`; POSIX commands use `./gradlew`.

```sh
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest :shared:ui:jvmTest
./gradlew :apps:android:assembleDebug :apps:android:lintDebug :apps:desktop:classes
./gradlew :apps:android:assembleDebugAndroidTest
# With a disposable booted Android test emulator/device:
./gradlew :apps:android:connectedDebugAndroidTest
./gradlew :apps:desktop:run
# macOS + Xcode only:
./gradlew -PenableIos=true :shared:ui:linkDebugFrameworkIosSimulatorArm64
```

Test meaningful invariants, mapping round-trips, failure paths and later persistence
and migrations. Do not add tests that merely mirror UI constants. Validate adaptive
layouts, large text, orientation and stylus behavior on real devices as relevant.
Keep writes off the UI thread. Each completed gesture is one queued transactional
edit; Saved must mean committed, not just visible. Keep failed/pending ink in memory
and support retry. Never redirect an asynchronous edit to the currently selected
date/page; every edit carries its original target. Immutable stroke identities and
source points must survive erasure undo exactly. Do not add destructive migration
fallbacks or flatten handwriting as primary storage.
Run applicable checks, fix failures, and report exact blockers if tools or network
are missing. Never call an unexecuted test passing. Keep docs/validation.md accurate.

## APK signing

Install/update artifacts must use the existing GitHub-secret-backed persistent key
and match config/android-signing-certificate.sha256. Never generate a key in CI or
silently fall back to an ephemeral signer. Keep signing secrets out of PR jobs, Git,
logs and artifacts; runner copies belong in protected temporary storage and must be
cleaned up. The installable artifact is my-journal-installable; diagnostic CI APKs
are explicitly ephemeral. Do not rotate the established key/pin without an explicit
user-approved migration plan. Losing signing identity can block in-place updates.

## Phase 4 data safety

To-do projections, immutable operations and device sequence updates commit together.
Deadlines are LocalDate. Preserve task identities and all unresolved operation heads.
Use explicit tombstones and authenticated transport for private cloud payloads. Keep
OAuth state in Android adapters and credentials outside Git. Drive backup is separate
from operation synchronization and must use the existing validated restore/recovery
flow. Keep reminder preferences per device. Distinguish JVM protocol coverage,
emulator tests, authenticated cloud tests and Xiaomi hardware results in validation.

## Phase 5 server boundary

Android is the supported app. Future browser support uses the PWA. Legacy desktop
and Apple launchers are unsupported. Keep server endpoints unconfigured by default.
To-do scope is the authenticated dataset and recovery epoch. Advance the cursor
with replay in one transaction. Clear pending changes only for explicit matching
receipts. In a bound database, allow local snapshot restore only if To-do
records match exactly and the sync history, outbox and checkpoint are preserved. Keep backup schema
independent of Room migrations. Check contracts with the local fixtures. Keep
Ktor, PostgreSQL, PWA implementation and deployment in Phase 6.

## Phase 6 milestones

Use the Phase 5 sync contract and preserve the Android signing identity. Test the
server against disposable PostgreSQL with apps/server/scripts/test-postgres.sh.
Never connect that suite to production. Keep new record types local until their
replay, conflict and migration rules pass. Deploy only after explicit approval.

For browser changes, run npm run typecheck, npm run lint and npm test in apps/web,
then run apps/web/scripts/test-browser.sh against its disposable stack. Keep
IndexedDB task and outbox changes atomic, retain local data across shell updates,
and never cache private API responses in the service worker.

For Android server sync changes, run the normal Android emulator suite and
`LIFEAPP_ANDROID_BRIDGE=1 apps/web/scripts/test-browser.sh` with a disposable
API 36 emulator. Keep test TLS credentials and server data temporary.

## Phase 7G protection

Keep sync, automatic backups and data export independent. Preserve reviewed
binding and outbox receipts. For managed retention, verify both survivors
before destructive remote cleanup and keep manual copies outside scope.
Use a disposable dataset and rclone destination for backup tests. Keep
Android recovery credentials independently recoverable and protected by
Keystore when cached. Never activate production timers or pruning from a PR.

## Phase 8 expenses

Use integer INR paise and a decimal string in portable JSON. Preserve label
references and expense tombstones. Update backup and restore compatibility
with every persistent schema change. Scope operations to the authenticated dataset and epoch. Commit projection and
outbox together. Preserve competing heads and clear pending operations only after
a matching receipt and replay. Schema 11 includes expense sync state and omits
credentials. Use disposable data for recovery checks.
