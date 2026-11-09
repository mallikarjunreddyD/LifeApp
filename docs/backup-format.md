# LifeApp portable backup archive v1

A full backup is a ZIP with `kind: backup`. No absolute Android/SDK paths or SQLite
files are stored, and no entry is extracted to an arbitrary filesystem location.

```text
manifest.json
data.json
ink/<stroke-UUID>.ljpt
```

## Manifest

`format: LifeAppArchive`, `backupFormatVersion: 1`, `dataSchemaVersion: 6`, `kind`,
`appVersion: 0.6.0`, `createdAt` (UTC Instant), sections (metadata, journal, planner,
habits, notebooks, ink, todos), counts and files. Counts keys: metadata, journalEntries,
journalPages, planners, habitLedgers, habitDefinitions, habitCompletions, notebooks,
notebookPages, strokes, todos. Every data.json/LJPT file has path, exact uncompressed byte
size and lower-case hexadecimal SHA-256. The manifest is not self-checksummed. ZIP
CRC is also verified. Checksums detect corruption; they do not authenticate the author
or encrypt private journal data. Use trusted backup files and private destinations.

## Records and vectors

Portable LifeAppRecords JSON v1/schema6 has all domain records and complete metadata;
see export-formats.md. ZIP strokes use pointsFile exactly `ink/<their UUID>.ljpt`.
LJPT1 retains Float32 coordinates/pressure and Int64 elapsed milliseconds exactly.
No flattening, timestamp/revision regeneration, identity replacement or archive-state
filtering occurs. Full backup includes Phase0 metadata, all dated journal pages,
planner layout/ink, all habit definitions/boolean records and all notebook pages,
including archived data, and active and deleted To-do records. UI draft/viewport/undo state is not persisted product data.

## Validation and bounds

Validation precedes database writes. Reader requires the central ZIP directory
(truncation is rejected), unique relative paths from the exact allowlist, no folders,
no traversal/absolute/backslash paths, no duplicate files or unexplained extras.
Manifest version/kind/schema/sections, declared file set/sizes/hashes/CRC and counts
must agree with decoded records. Ink files must each be referenced exactly once.
Domain validation checks UUID uniqueness, LocalDate/month uniqueness, ownership/FKs,
page/stroke orders, geometry/layout versions, completion habit/date uniqueness,
record metadata, supported record schema1 (legacy metadata may retain its original
positive schema), LJPT1, finite coordinates/widths/pressure, monotonic times and cell
ownership. Unknown formats fail visibly; existing data is never reinterpreted.

Limits apply before unbounded allocation: 64 MiB compressed archive and expanded total;
8 MiB each for manifest/record JSON; ≤50,002 ZIP files; ≤50,000 records;
≤2,000,000 points total; ≤1,000,000 points per stroke (≤20,000,012 LJPT bytes).
JSON nesting is checked before tree parsing (≤32). Reads are bounded even if ZIP
metadata lies. Oversized complete snapshots/archives are refused with an error,
never silently truncated. These are current implementation limits, not capacity
claims for unlimited histories. General JSON/native/Markdown export has the same
snapshot record/point limits and ≤64 MiB output; PDF has its own output/page limits.

Common code owns records, JSON, validation and version rules. Android/JVM share the
standard-library ZIP/SHA/file adapter in jvmAndAndroidMain; a future iOS client needs
its own archive adapter but can reuse common records and LJPT unchanged. The optional [encrypted Drive wrapper](drive-backup.md) stores the unchanged ZIP payload. Restore remains a local replacement.

## Phase3 archive compatibility

Archive format stays 1. Schema 5 introduced dataSchemaVersion5 in manifest/data.json
and include a boolean archived on every journal page. Manifest and payload schema
versions must match. Schema5 requires the visibility field; when reading schema4, this
app defaults missing journal visibility to false. Readers accept schemas 4, 5 and 6. Unknown versions are rejected. Frozen Phase3 ZIP/JSON fixtures generated using the original
codec prove backward reading and restoration. Previously exported schema4 backups
remain supported. Older Phase3 apps cannot read new schema5 exports; keep an original
Phase3 backup and do not downgrade an upgraded database.

All journal pages, archived or active, their original order and exact LJPT bytes are
included. At least one active page per journal is validated before restore. Restore
preserves archived flags, metadata/IDs and indexes; it never drops hidden vectors.
Room database migrations are 1→2→3→4→5→6. Schema 6 writes a required `todos`
section with task records and a matching count. Schemas 4 and 5 decode to an empty
task list. Schema 6 preserves task UUIDs, calendar deadlines, revisions, UTC
timestamps and deletion flags. Local operation history, conflict variants, account
state and reminder settings are outside the portable snapshot.

## Phase 5 compatibility

Room schema 7 adds only a synchronization checkpoint table. Portable JSON and ZIP
writers still use data schema 6 and encrypted backups still use LIFEBAK1. Readers
retain schema 4, 5 and 6 support. Server scope, cursors and outbox history stay out
of portable backups. Restore into a separate local database when server-bound.
