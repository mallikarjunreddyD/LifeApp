# Roadmap

Phase 2 implements the Monthly Planner, customizable habits and Daily Journal links.
Do not begin Phase 3 automatically. Physical Xiaomi checks remain explicitly deferred.

1. **Phase 0/0.1 — Foundation:** modular KMP, Android/JVM launchers, opt-in Apple
   targets, metadata, tests and corrected supported Android SDK provisioning.
2. **Phase 1 — Daily Journal:** dated ruled vector pages, Ink pen/highlighter/eraser,
   automatic persistence, multiple pages, exact Undo/Redo and error/retry handling.
   PR #2 was merged before Phase 2 branched from updated main.
3. **Phase 2 — Planner and habits:** canonical handwritten month calendars,
   independent customizable monthly checkbox ledgers, archive/restore/copy,
   typed date links and Back restoration, bounded lazy journal cache, Room schema3
   and non-destructive migrations. Actual automated results are in validation.md.
4. **Later — Free Notebook and user-controlled backup/export.**
5. **Later — Companion clients and synchronization:** iOS and Windows initially
   mostly viewing. Provider/conflict/backup decisions require separate design.
6. **Later — Gym and expense trackers.**
7. **Later — Read-only Google Calendar:** explicit consent and multiple accounts.

Future directions are unimplemented. No cloud provider, AI recognition, Daily
Highlights, gamification, account system or online service was added. Each next
phase needs explicit authorization and its own acceptance/tests/documentation.
