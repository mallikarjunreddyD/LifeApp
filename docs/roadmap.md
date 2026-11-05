# Roadmap

Phase 3 adds Free Notebook, PDF/structured exports and validated local backup/restore.
Do not begin Phase 4 automatically. Physical Xiaomi checks remain explicitly deferred.

1. **Phase 0/0.1 — Foundation:** modular KMP, Android/JVM launchers, opt-in Apple
   targets, metadata, tests and corrected supported Android SDK provisioning.
2. **Phase 1 — Daily Journal:** dated ruled vector pages, Ink pen/highlighter/eraser,
   automatic persistence, multiple pages, exact Undo/Redo and error/retry handling.
   PR #2 was merged before Phase 2 branched from updated main.
3. **Phase 2 — Planner and habits:** canonical handwritten month calendars,
   independent customizable monthly checkbox ledgers, archive/restore/copy,
   typed date links and Back restoration, bounded lazy journal cache, Room schema3
   and non-destructive migrations. Actual automated results are in validation.md.
4. **Phase 3 — Notebook and data protection:** named notebooks, ordered/titled/archived
   pages with four templates, reused Ink, PDF/JSON/Markdown/native export, versioned
   checksum-validated local ZIP backup and atomic restore with verified recovery.
   Actual execution evidence and remaining limits are in validation.md.
5. **Later — Companion clients and synchronization:** iOS and Windows initially
   mostly viewing. Provider/conflict/backup decisions require separate design.
6. **Later — Gym and expense trackers.**
7. **Later — Read-only Google Calendar:** explicit consent and multiple accounts.

Future directions are unimplemented. No cloud provider, AI recognition, Daily
Highlights, gamification, account system or online service was added. Each next
phase needs explicit authorization and its own acceptance/tests/documentation.

Phase3.1 corrective work addresses tablet ink/workspace matrix resolution and safe
journal-page archival before Phase4. Physical accuracy retesting is pending; the
architecture/format/validation documents distinguish software evidence from hardware
measurement. No Phase4 implementation is authorized by this correction.
