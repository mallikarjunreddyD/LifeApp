# Architecture — Phase 1

Android on Xiaomi Pad 7 is the primary editing platform. Shared modules retain
Android/JVM targets and explicitly opt-in iOS arm64 targets via `-PenableIos=true`.
No Xcode is required for Android/JVM development. Phase 0.1's SDK provisioning fix
was merged at main commit 3eb4108 before Phase 1 began.

## Boundaries

- apps/android owns the native MotionEvent/Jetpack Ink adapter, Daily Journal
  Compose screen, Android lifecycle and application composition root.
- shared/ui owns the paper palette, typography and existing adaptive navigation.
  JournalApp accepts an optional composable for the Daily destination; Android
  supplies its editor. Desktop/iOS keep that destination as an honest placeholder.
- shared/domain owns portable journal/page/vector models, repository contract,
  logical transforms, spatial hit index, exact undo/redo and JournalEditor state.
  It has no Android, Compose, Room or Jetpack Ink imports.
- shared/core retains UUID/UTC timestamp/schema/revision invariants.
- shared/data owns Room tables/DAOs, versioned point encoding, local repository,
  explicit migration and the Android database builder. It exposes domain types to
  application composition; the UI never accesses a DAO or database.

Dependencies point inward: Android → shared UI/data/domain; UI/data → domain →
core. No circular dependencies, account, backend, networking or cloud service is
introduced. The Android manifest requests no Internet permission. Automatic system
backup remains explicitly disabled until backup is designed in a later phase.

## Input and rendering

JournalCanvas is a native FrameLayout with separate finished-ink/paper and live-ink
Views. The finished View keeps a hardware display layer; per-sample input updates
only the live Ink InProgressStroke. Stable Ink 1.0.0 brush/strokes/rendering APIs
handle progressive geometry, smoothing, pressure and drawing. Stock pressure-pen
V1/highlighter V1 brush versions are explicit. Ink authoring-compose and 1.1 alpha
APIs are not needed; no working core dependency was downgraded.

MotionEvent tool types separate stylus/eraser from fingers. Historical samples are
transformed from screen pixels to a fixed logical page; a uniform transform is
inverted for capture and applied for rendering. Gestures pan and zoom 1–4× without
changing stored points. Input cancellation discards the active gesture. Pen contact
requests unbuffered dispatch. Pressure is normalized where present and falls back
to full width if unavailable. Stylus/hover suppresses finger panning; palm rejection
is limited to signals the device actually reports.

Completed native stroke meshes are cached by stroke UUID. Reloaded meshes are
reconstructed on Dispatchers.Default from portable vectors; they are never duplicated
in storage. Whole-stroke erasing uses incrementally updated logical spatial buckets
and segment hit tests. Eraser previews are committed as one edit on release;
canceling restores the unchanged saved layer. Saved strokes are not re-generated
for each input sample. Physical large-page throughput/latency still needs measurement.

## Storage and reliability

Room schema 2 retains the Phase 0 table and adds unique-date entries, ordered pages
and ordered immutable strokes. MIGRATION_1_2 is explicit and non-destructive. Both
schema exports are committed. Journal observation initializes/migrates the database
before starting invalidation observation. Tests cover the actual schema 1 upgrade,
including old metadata preservation. See handwriting-format.md for the precise
platform-independent point BLOB and record fields.

A viewed blank date stays an in-memory draft until the first stroke or explicit
Add Page. Adding a second page to an unwritten date persists both page descriptors.
The repository rejects duplicate-date identity conflicts, invalid parent/order
relationships and replacement of an immutable stroke ID. Writers are transactional;
failed multi-stroke changes roll back data and metadata together.

JournalEditor owns per-date/page drafts and history, and serializes PageEdits in an
application-owned main-dispatcher scope that outlives Activity recreation. Database
I/O and encoding run off the input/UI thread. Each queued edit carries its own date,
page and immutable vectors, so navigation cannot redirect a delayed stroke. “Saved”
requires successful completion of every queued transaction. A failure keeps the
queue head and all optimistic ink in memory, shows an error, and pauses writes until
Retry. Idempotent replays prevent duplicate ink after uncertain acknowledgment.

The Android ViewModel/SavedStateHandle preserves selected date/page and current
viewport through ordinary recreation; per-page viewports and histories remain in
memory. Date identity is a LocalDate, independent of UTC metadata conversion.
Opening a cold session defaults to the current local date. Process restart reloads
saved vectors from Room; undo/redo history and uncommitted work are not a disk journal.
A forced kill before Saved can lose pending/in-progress edits. No loss-free claim
is made for unfinished transactions or power failure.

Monthly Planner, Habit Tracker, Notebook and Settings remain unfinished placeholders.
There is no page deletion/reordering, export, handwriting recognition, synchronization
or Phase 2 functionality. Desktop still compiles but is not a journal viewer yet.
