# Editable handwriting storage, version 1

LifeApp stores vectors, not page screenshots. This is an application-owned format,
not a universal `.ink` standard; no export/import UI is implemented in Phase 1.
No Android, Compose, Ink JNI object, bitmap or generated mesh is persisted.

## Identity, geometry and order

Journal dates use kotlinx.datetime.LocalDate, stored as ISO calendar dates such as
`2026-11-03`. They are not UTC instants. A unique database index enforces one entry
per date. Entry, page and stroke IDs are independent UUIDs, never list positions.
Created/updated timestamps are UTC Kotlin Instants. Positive record schema versions
and revisions are separate from Room's database version (currently 2).

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
