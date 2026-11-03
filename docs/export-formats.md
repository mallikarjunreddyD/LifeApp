# Export formats — version 1

All Android outputs use ACTION_CREATE_DOCUMENT through Activity Result contracts.
No broad storage permission is requested. Names contain a sanitized descriptive
prefix, timestamp and short UUID; the system picker creates a distinct document on collision.
The application writes off the main thread, then rereads the selected URI and compares
length/SHA-256 against the prepared file. Failed writes/verification never report
success. A provider may leave a partial document after failure: discard it and retry.

## PDF

Settings offers a saved Daily Journal date or inclusive date range (sorted by date
and page order), a saved planner month, one notebook page, or the entire notebook
(sorted by stored order, including labelled archived pages). Empty selections are
disabled/rejected. Export uses a fresh consistent committed snapshot, not screenshots.

PdfExport uses Android PdfDocument and standard Canvas paths. It does not call the
hardware Jetpack Ink renderer on a PDF canvas. PaperDrawing/CalendarPaper share
programmatic backgrounds with editing. Each PDF page has canonical logical width
and height plus a 48-unit title strip above the unchanged paper coordinates. Planner
ink is clipped to its owning cell. Colour/alpha/order/positions are retained. Pen
pressure uses a round pressure envelope (width×(0.25+0.75p)); highlighters draw one
round path using their stored alpha. Missing pressure uses full width. Brush edge
smoothing and pressure appearance approximate native Ink rather than promise identical
meshes; original vectors remain unchanged/editable. No handwriting recognition.

PDFs allow ≤500 pages and ≤128 MiB output. Pages process sequentially and check
cancellation; PDF resources/files close on failure. Pixel tests rasterize generated
PDFs with Android PdfRenderer to assert visible ink, pen colour, highlighter alpha,
page dimensions, notebook ordering, paper marks and calendar placement.

## Portable JSON

SnapshotJson lives in shared/data common code and uses stable kotlinx.serialization
JSON tree APIs (1.11.0); no Android/Room file representation is exposed. Header:
`format: LifeAppRecords`, `formatVersion: 1`, `dataSchemaVersion: 4`.
Top-level arrays: metadata, journals (with pages), planners (with layout), habits
(with definitions and completions), notebooks, notebookPages, strokes.

Every record carries metadata `{id,createdAt,updatedAt,schemaVersion,revision}`.
UUIDs and UTC Instants are strings, journal/completion dates are ISO LocalDate,
months are YYYY-MM. Page ownership/order/dimensions/template, notebook descriptions/
archive flags, calendar geometry, habit category/colour/target/order/archive and
checked/unchecked completion records are explicit. Stroke fields are metadata,
surfaceId, order, brush, colorArgb, width, pointFormatVersion and pointsBase64 (LJPT1).
Timestamps retain nanoseconds; Long revisions/ARGB are not floating-point conversions.
Arrays use canonical ordering; names and positions are never identities.

## Native vectors

A `kind: vectors` LifeAppArchive ZIP contains the same complete portable records,
manifest and separate ink/UUID.ljpt files instead of inline Base64. Stroke metadata
remains in data.json, preserving brush/width/order/identity/ownership. This is an
application-owned format, not a universal Ink standard. It is not accepted by the
full-backup Restore control; use `kind: backup` for restoration. A future platform
can read JSON/LJPT without Android Ink or Room. See backup-format.md.

## Markdown

Markdown includes journal dates/page IDs, planner geometry identity, notebook/page
titles/templates/archive labels, habit names/counts/category/targets and all record
metadata. Its opening notice states that handwriting is not converted to text and
this is not a complete backup. Obtain PDF for visual content or native vectors/full
backup for editable ink. No nonexistent companion file is advertised as generated.
User text is escaped for Markdown/HTML display. JSON/native/Markdown export covers
all saved records; it does not omit archived or unchecked records.

## Dependency/API references

- [Kotlin serialization setup](https://kotlinlang.org/docs/serialization-get-started.html)
- [Serialization 1.11.0 release notes](https://github.com/Kotlin/kotlinx.serialization/blob/master/CHANGELOG.md#1110--2026-04-10)
- [Android PdfDocument](https://developer.android.com/reference/android/graphics/pdf/PdfDocument)
- [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [Android document picker](https://developer.android.com/training/data-storage/shared/documents-files)

Existing Kotlin, Compose, AGP, Gradle, Room, SQLite and Ink versions are retained.
