# Phase 9A structured synchronization

Settings → Synchronization connects To-do, Expenses and Workouts in one
review. The browser commits the connection in one IndexedDB transaction.
Android verifies one recovery ZIP before enabling newly reviewed modules.
Existing bindings and pending changes carry forward. First transfer of an
Android habit month still presents the applicable upload or download review.

**Sync now** checks To-do, Expenses, Workouts and Habits. Android schedules
connected work on foreground, local commits, reconnection and active refresh.
The browser catches up while active or when reopened. Journal, planner and
notebook remain on Android. Previously committed archives and their readers
remain available to older installations.

## Workouts

The structured workout endpoint uses immutable, dataset-scoped server versions
with a compare-and-swap head for each exercise, routine or session. Local saved
records remain editable offline. Room and IndexedDB retain the last confirmed
version, so independent records merge and a changed copy never silently
overwrites another device's edits. Review both copies before choosing a version.
An active draft prevents replacement of that session.

Flyway V11 adds the version history and current heads. PostgreSQL backup
manifest v4 verifies both counts. Older manifest readers remain supported by
the current restore tool. A clean Android restore recovers saved workout data.
Reconnect workouts to review the recovered local records against the server.
A replacement of an already connected database must match its saved workout
ledger exactly. Existing read-only server workout records remain available in
their original endpoints.

## Habits

The browser stores previously opened months and a scoped checkmark outbox in
IndexedDB. Changes survive a reload, retain their operation IDs across retries,
and are removed only after a confirmed receipt and a readable committed version.
Settings sync and reconnection process the same queue. Cached months and pending
checkmarks are included in the browser export. The server continues to use its
existing habit completion and conflict rules, preserving habit IDs and older
month versions. Edit definitions in Android and open the month in the browser
while online before working with it offline.
