# Editable handwriting storage, version 1

LifeApp stores vectors, not page screenshots. This is an application-owned format,
not a universal `.ink` standard. Phase3 exports PDF/JSON/native ZIP and imports full
backups; see export-formats.md and backup-format.md.
No Android, Compose, Ink JNI object, bitmap or generated mesh is persisted.

## Identity, geometry and order

Journal dates use kotlinx.datetime.LocalDate, stored as ISO calendar dates such as
`2026-11-03`. They are not UTC instants. A unique database index enforces one entry
per date. Entry, page and stroke IDs are independent UUIDs, never list positions.
Created/updated timestamps are UTC Kotlin Instants. Positive record schema versions
and revisions are separate from Room's database version (currently 4).

Pages have an entry UUID, stable UUID, unique zero-based order within the entry,
width/height and RULED paper template. The Android editor currently creates
1000 × 1414 logical-unit pages. Width, height and source points stay unchanged on
zoom, density changes and rotation. Pages are appended; reordering/deleting pages
is intentionally not exposed in Phase 1.

Each immutable stroke has a UUID, page UUID, nonnegative Long order, PEN or
HIGHLIGHTER, unsigned 32-bit sRGB ARGB stored in a Long, logical brush width,
record metadata and formatVersion=1. The editor assigns monotonically increasing
orders; deleting and restoring a stroke preserves its exact ID, order, properties,
points and metadata. Reopening a page loads strokes sorted by order.

## LJPT point BLOB

Each stroke's points are stored once in its Room row. All numbers are little-endian.
The byte length must equal `12 + count * 20` exactly. Extra/truncated bytes, unknown
versions, invalid floats, invalid pressures and unordered times are rejected and
surfaced as load errors, never silently converted to blank pages.

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | 4 bytes | ASCII `LJPT` (magic integer 0x54504A4C) |
| 4 | Int32 | Point format version, currently 1 |
| 8 | Int32 | Point count, 1 through 1,000,000 |
| 12 + 20n | IEEE Float32 | Logical x |
| 16 + 20n | IEEE Float32 | Logical y |
| 20 + 20n | IEEE Float32 | Pressure [0,1], or -1 for unavailable |
| 24 + 20n | Int64 | Milliseconds since this stroke began |

Coordinates and widths must be finite; width is in [0.1,100]. Input is clipped to
the logical page rectangle. Elapsed times are nonnegative and strictly increasing.
Android historical samples are retained in event order; duplicate/non-increasing
hardware times are normalized to the previous elapsed time +1ms. Sensor pressure
is normalized by a reported maximum above 1, then clamped to [0,1]. Missing pressure
is retained as null/-1; rendering uses full pressure as its fallback.

Brush appearance for format 1 is reconstructed with stable Jetpack Ink 1.0.0:
StockBrushes.pressurePen V1 or highlighter V1, epsilon=0.1 logical unit. The UI gives
highlighters alpha 0x55. Other platforms can consume the vector format without
importing Ink. Exact cross-platform brush rendering is not implemented yet.
Future brush/point formats must use a new version and preserve the ability to read
existing content; never reinterpret or overwrite unknown versions.

## Database consistency

Room 2 adds journal_entries, journal_pages and ink_strokes. Foreign keys protect
parent relationships; unique indexes protect dates and per-parent order. The
Phase 0 record_metadata table and exported schema 1 remain unchanged. Explicit
MIGRATION_1_2 creates new tables/indexes without deleting old data; exported schema 2
is committed. The migration test starts from the actual schema 1 create SQL and
identity hash and checks that existing metadata survives.

A completed stroke, a whole-stroke erasure, an undo/redo or an explicit Add Page
is one queued PageEdit, committed in one writer transaction. Page/entry revisions
advance for mutations; first creation starts at revision 1. Replaying an identical
stroke insert or already-applied deletion is a no-op, so a retry after uncertain
acknowledgment does not duplicate ink. Replacing an existing stroke ID with different
content is rejected and the whole transaction rolls back. Eraser undo reinserts
the original immutable stroke, while page/entry revisions advance.

## Monthly planner ownership and geometry (Phase 2)

Planner strokes use the same LJPT version 1 BLOB and Ink brush versions. No point
format change or existing journal-vector rewrite occurs. planner_ink_strokes has a
surfaceId FK to planner_pages, never journal_pages. Its domain InkStroke.pageId is
that planner surface UUID. Stable cellDay plus the first logical point identify the
owning date; repository validation rejects inconsistent/cross-cell data.

planner_pages stores a UUID, ISO year-month, layoutVersion=1, row count, width1400,
headerHeight70, cellHeight120 and record metadata. Version 1 has seven 200-unit
columns, Monday first, and max(5, ceil((Monday offset + days)/7)) rows. Page height
is 70 + rows×120. A day cell begins at column=(offset+day−1)%7 and
row=(offset+day−1)/7. Unknown or inconsistent geometry is rejected visibly, never
reinterpreted. A future template change requires a new layout reader/version.

Capture transforms screen samples into this canonical coordinate system. A stroke
starts only in a valid date cell and stays clamped to it; rendering clips its brush
to that cell. A finger tap in the upper-left 100×90 logical-unit date target opens
its journal; stylus input there remains ink. Blank dates never create journal rows
just by viewing them. Month, orientation and zoom changes never rewrite source points.

Room MIGRATION_2_3 only adds planner/habit tables and indexes. Journal schema 2 SQL,
IDs, metadata and stroke bytes remain unchanged; old schema exports are preserved.
Tests cover 1→2→3 and 2→3 and real-file planner vectors across reopen/erase/restore.

## Notebook and portable export (Phase 3)

Notebook pages use separate FK-owned notebook_ink_strokes but the same InkStroke,
InkPoint and unchanged LJPT1 bytes. Stored logical dimensions and BLANK/RULED/DOTTED/
GRID templates are independent of vector coordinates. Template changes never rewrite
ink. Archives/backups preserve stroke/owner IDs, metadata, brush, alpha, width and order.
JSON includes pointsBase64; native/full-backup ZIP uses pointsFile and ink/UUID.ljpt
plus full stroke metadata. Schema4 migration is additive; schemas1–3 are unchanged.

PDF uses a dedicated Canvas path pressure envelope/highlighter adapter with clipping,
not the native on-screen Ink renderer. It is a visual export with approximate brush
edge smoothing, not an editable native stroke interchange file. Stored vectors remain
editable. Markdown has no handwriting text or editable vector representation.
