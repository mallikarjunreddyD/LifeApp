# Roadmap

Phase 1 Daily Journal implementation is included. Do not start Phase 2 automatically.

1. **Phase 0 — Foundation:** modular KMP project, Android launcher, prepared iOS/JVM
   hosts, metadata and repository contracts, paper shell, tests and build workflow.
   Compilation and platform checks remain subject to the validation report.
2. **Phase 1 — Daily journal:** Android Jetpack Ink adapter, dated ruled pages,
   durable editable vector storage, pen/highlighter/eraser and exact undo/redo.
   Automated verification is recorded in validation.md. Physical Xiaomi latency,
   palm, pressure and long-page checks remain pending before expanding scope.
3. **Phase 2 — Planner and habits:** monthly pages, variable month lengths, habits,
   checkbox ledger and navigation to daily pages.
4. **Later — Notebook and user-controlled backup/export.**
5. **Later — Companion clients and synchronization:** iOS and Windows mostly view
   only; startup/manual downloads and local-first writes with eventual uploads.
   Select a provider after evaluating backup and conflict requirements.
6. **Later — Gym and expense trackers:** structured editing on Android and iOS;
   Windows remains a viewer initially.
7. **Later — Read-only Google Calendar:** multiple accounts, explicit consent.

These future directions reflect the referenced planning chat; they are not working
features or a commitment to any cloud backend. Each phase needs its own acceptance
criteria, device checks, tests, documentation and explicit user authorization.
