# Free Notebook — Phase 3

Notebooks have UUID/RecordMetadata, trimmed name (1–120 characters), optional
description (≤2000), default paper template and archived flag. Each page has its own
UUID, notebook UUID, unique nonnegative display order, optional title (≤120), stored
template, dimensions, metadata and archived flag. New pages use 1000×1414 logical
units. Create includes one empty page, never a fake stroke. A page can override the
creation-time notebook template. All four backgrounds are generated programmatically.

notebooks → notebook_pages → notebook_ink_strokes are separate FK ownership tables.
This additive approach avoids changing existing journal/planner stroke ownership.
The existing InkStroke/InkPoint and LJPT1 codec are reused without format changes.
Room schema4/MIGRATION_3_4 adds only these tables and two unique order indexes;
schemas1/2/3 and all earlier migrations remain unchanged.

Notebook list opens active or explicitly shown archived notebooks. Notebook/page
creation, rename, title, paper, reorder and archive/restore autosave in transactional
FIFO edits. Archival never deletes vectors. Entire-notebook PDF includes archived
pages, labelled as such; all backups include archived content. There is no permanent
deletion or destructive confirmation path. Reorder preserves UUIDs and temporarily
stages negative SQL orders inside its transaction before restoring valid positions.

NotebookScreen binds page.surface to the same JournalCanvas as journal and planner.
PaperDrawing supplies blank/ruled/dotted/grid/calendar backgrounds to native view and
PDF. Template changes invalidate paper rendering without changing coordinates or ink.
Tools, pressure, finger pan, pinch zoom, cancellation and exact eraser undo are shared.
The default template is chosen at notebook creation; editing an existing page affects
only that page. Notebook editing is Android-only in this phase.

NotebookEditor lazily loads selected-page vectors, keeps at most three unprotected
saved histories and four unprotected notebook drafts, and protects pending edits,
in-flight reads, active and native-bound surfaces. Soft caps may be exceeded while
work is pending. Native release commits before unpinning. Recently selected notebook/
page and UUID-keyed viewport remain across ordinary navigation/Activity recreation.
Android Back inside a notebook returns to its list. Saved vectors reload from Room;
Undo/Redo is memory-only and may be unloaded with a saved history. As elsewhere,
uncommitted work is not guaranteed through unexpected process death.
