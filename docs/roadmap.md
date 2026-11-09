# Roadmap

Phase 3 adds Free Notebook, PDF/structured exports and validated local backup/restore.
Physical Xiaomi checks remain explicitly deferred.

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
5. **Phase 4 — To-do, reminders and Drive backup:** offline To-do editing, local
   reminders, schema 6, encrypted manual Drive backup and a provider-independent
   To-do outbox/replay core. Live sync and authenticated Drive acceptance remain
   open. See [protocol and limits](phase-4-sync.md).
6. **Phase 5 — Server preparation:** versioned REST contract, fake-server To-do
   synchronization, Room checkpoints, shared workout models and recovery design.
7. **Phase 6 — Self-hosted release:** Ktor, PostgreSQL, a React/TypeScript PWA for
   iPhone and desktop browsers, Android integration, workout editing and automated
   recovery. See the [implementation plan](phase-6-implementation-plan.md).
8. **Later — Read-only Google Calendar:** explicit consent and multiple accounts.

Future directions require explicit authorization and their own acceptance checks.

Phase3.1 corrective work addresses tablet ink/workspace matrix resolution and safe
journal-page archival before Phase4. Physical accuracy retesting is pending; the
architecture/format/validation documents distinguish software evidence from hardware
measurement.

Separate native iOS and Windows apps are outside the release plan.
