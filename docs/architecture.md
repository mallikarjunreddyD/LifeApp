# Architecture

Phase 3.1 branches from merged Phase 3 main2c82a17. Android on Xiaomi Pad 7 remains
primary. Shared Android/JVM targets build without Xcode; iOS arm64 targets remain
opt-in with `-PenableIos=true`. Existing toolchain/core dependency versions are unchanged; stable serialization JSON1.11.0 is added.

## Boundaries

- apps/android owns four Compose editing screens and Settings, MotionEvent/Jetpack Ink adapters,
  Android lifecycle/SavedStateHandle and the application composition root.
- shared/ui owns paper colours, typography and adaptive navigation. JournalApp takes
  optional journal/planner/habit slots plus explicit selected-section state. Android
  supplies working editors; desktop/iOS show honest unimplemented placeholders.
- shared/domain owns portable records, calendar geometry, typed navigation arguments,
  repository contracts and editor/history/queue state. No Android, Compose, Room or
  Jetpack Ink imports enter core/domain.
- shared/core owns UUID, UTC timestamp, schema-version and revision invariants.
- shared/data owns Room tables/DAOs, LJPT encoding, transactions and repositories.
  Screens never access databases; application composition injects domain interfaces.

Dependencies point inward: Android → UI/data/domain; UI/data → domain → core.
No accounts, network permissions or cloud integration. User-controlled local backup/export
is implemented; automatic Android system backup remains disabled. iOS/Windows editing
remain unfinished.

## Reusable Android handwriting

JournalCanvas accepts an InkSurface: stable ID, logical dimensions and optional
CalendarLayout. Daily pages supply ruled paper; planners supply the generated
calendar. Both use the same native wet/dry layers, pressure/highlighter brushes,
historical sample capture, 1–4× uniform transforms, UUID mesh cache, off-thread mesh
reconstruction, spatial whole-stroke eraser and gesture commit/cancellation handling.
Reattaching a view restarts interrupted mesh reconstruction. No duplicated engine,
bitmap storage, recognition or Ink 1.1 alpha dependency was introduced.

Planner geometry version 1 is fixed Monday-first, 1400 logical units wide, header70,
cell200×120, five or six rows. Geometry/version/row count are stored with the month;
screen density, zoom and orientation affect only the viewport. Landscape fits the
whole page; portrait initially zooms to keep cells readable and supports panning.
Stylus ink is clamped/clipped to its starting date cell. Header/unused cells cannot
start notes. A finger tap in a date-number target navigates; drag/pinch cannot navigate
or create ink. Whole-stroke erasure and undo retain exact stroke identity/properties.
See handwriting-format.md for coordinates and serialization.

## Persistence and migration

Room schema 3 retains all four schema 2 tables unchanged: record_metadata,
journal_entries, journal_pages and ink_strokes. Explicit MIGRATION_2_3 adds:

| Table | Ownership and uniqueness |
| --- | --- |
| planner_pages | UUID, unique ISO month, geometry/version/rows, record metadata |
| planner_ink_strokes | FK to planner_pages, cellDay, unique surface/order, LJPT BLOB |
| habit_ledgers | UUID, unique ISO month, record metadata |
| habit_definitions | FK to ledger, unique ledger/order, name/category/colour/target/archive |
| habit_completions | FK to habit, unique habit/date, checked boolean, record metadata |

Separate planner stroke ownership avoids invalid journal-page FKs and a risky redesign
of existing journals. All three stroke tables use identical portable vectors/LJPT1 encoding.
Schemas 1/2 remain committed unchanged; schema 3 is exported. MIGRATION_1_2 remains
registered so both 1→2→3 and 2→3 work without destructive fallback. Tests construct
real old-schema files, preserve old IDs/revisions/timestamps/bytes and add new content.

Month configuration is independent. Habits retain UUIDs through rename/reorder/archive;
archive removes the visible column and permits restoration without deleting history.
There is no permanent-delete path. Explicit copy uses active definitions only, fresh
UUIDs, zero completions and targets capped to the new month's days (disclosed in UI).
Each checkbox toggles the latest optimistic value, then queues the desired boolean.
Completion UUIDs stay stable even through unchecked states. Counts use checked records;
percentages divide by all calendar days. Transactions reject cross-ledger identities,
duplicate dates/orders and conflicting immutable stroke identities. Reorder temporarily assigns negative SQL
orders within its transaction to avoid uniqueness collisions; readers see atomic state.

## Saving, memory and lifecycle

Application-owned main-dispatcher editor scopes outlive Activity recreation. Immutable
queued edits carry original date/month/surface IDs, so late gestures cannot be redirected
by navigation. I/O and vector encoding run off the UI thread. Writes are FIFO; a failure
retains the head and subsequent edits, displays an error globally, and requires Retry.
Idempotent exact replay avoids duplicates after uncertain acknowledgment. Saved status
requires successful persistence, not optimistic visibility. Read failures show an error
instead of an editable replacement blank surface.

JournalEditor lazily loads only the selected page's strokes; other page descriptors
remain available for navigation. Saved date drafts use an LRU soft limit of four,
loaded page histories a soft limit of three. Active, pending, loading and native-bound
surfaces are protected; unsaved changes and pending writes are never evicted to meet
a cap. Native release commits its last gesture before unpinning. Selected-page IDs
are remembered for the most recent 128 dates. Reload restores vectors from Room;
evicted saved histories lose session Undo/Redo. Planner drafts use a four-month saved
cache; habit snapshots use six months. Pending work can exceed these soft caps.

Typed AppRoute arguments carry LocalDate/JournalMonth, independently of UTC metadata.
AppNavigator keeps an explicit bounded back stack in SavedStateHandle. Cross-module
links push; top-level navigation selects a root; date/month changes replace the top.
Back restores the originating month. Viewports are keyed by surface UUID in the
ViewModel. Habit vertical row/offset and horizontal offset are saved per month, including
Activity recreation. Existing journal date/page/current viewport restoration remains.

Successful commits survive database reopening. Queues and undo stacks are in application
memory, not a durable write-ahead edit journal. Unexpected process termination before
commit can lose pending/in-progress work. Physical Xiaomi latency, palm rejection,
pressure, long-page performance and acceptance checks remain deliberately deferred.

## Phase 3 additions

Notebook models/editor live in domain, separate notebook Room tables in data, and the
Android NotebookScreen reuses JournalCanvas. Shared paper rendering supplies four
procedural notebook templates. Schema4 adds only notebooks/notebook_pages/
notebook_ink_strokes; MIGRATION_3_4 preserves the full1→2→3→4 chain. See notebook-design.md.

JournalSnapshot is a portable complete record graph with validation/canonical ordering.
RoomSnapshotRepository reads it atomically and replaces records in one writer transaction
without regenerating metadata. SnapshotJson, manifest semantics and LJPT are portable;
ZIP/SHA/file operations share Android/JVM code. Android owns SAF and PdfDocument adapters.
The default KMP source hierarchy remains applied and Apple targets remain opt-in.

Application-owned DataOperations blocks UI input while awaiting all editor queues and
performing capture/verification. Restore validates staging before writing, requires an
externally verified recovery ZIP for populated replacement, then cancels old editor
scopes and rebuilds all caches after its atomic transaction. No stale queue survives
restoration. Settings exposes progress/error/success and explicit two-step warnings.
No UI screen accesses Room/DAOs; the composition root injects repositories/operations.
See backup-format.md, backup-restore.md and export-formats.md for precise bounds/limits.

## Tablet correction and journal archive

Phase3.1 branches from merged Phase3 main2c82a17. A confirmed inherited View.matrix
shadowing defect caused paper/native ink to render in logical pixels while input
used the correct inverse transform. Both child layers now explicitly use the outer
pageToViewMatrix for Canvas.concat and the Ink renderer hint; full details and pinned
API evidence are in tablet-ink-alignment.md. No vector or calendar geometry changes.

Room5/MIGRATION_4_5 add journal_pages.archived NOT NULL DEFAULT0 without rewriting
any earlier row or point BLOB. JournalEntry retains all pages and exposes sorted active/
archived lists. Delete/restore is a separately targeted FIFO PageEdit.setArchived
command. Ordinary late stroke edits cannot unarchive a page through stale descriptors.
Both editor and repository enforce at least one active page. Appending uses the
maximum original order+1, never a reused archived position. Creation metadata and
stroke metadata/UUIDs remain; page/entry modification revisions advance on visibility
changes. Backups and labelled PDFs include archived pages. Wire schema5 explicitly
stores visibility; readers still accept schema4 as all-active. See backup-format.md.

## Phase 4 additions

Room schema 6 adds To-do records, operation history and a local device sequence
without changing existing tables. Shared/domain owns tasks, grouping, reminders
and the provider/coordinator contracts. Shared/data owns the projection, durable
outbox, causal replay, versioned JSON and backup encryption. Android owns the
To-do surface, alarm and notification adapter, Google authorization and Drive
REST. An encrypted cloud backup goes through the same validated ZIP and confirmed
local restore operation as a file from the Android picker. Live synchronization is
disabled pending the safety work documented in [the protocol](phase-4-sync.md).

## Phase 5 boundaries

Android remains offline-first with no server configured. Shared/domain defines the
To-do transport, checkpoint, coordinator and workout validation. Shared/data applies
To-do pages and persists a bound scope and cursor transactionally in Room schema 7.
Separate receipts acknowledge outbox entries. Android backup/restore format stays
at portable schema 6. The legacy native companion launchers are unsupported.
See [server architecture](server-architecture.md) and [conflict policies](record-conflicts.md).
