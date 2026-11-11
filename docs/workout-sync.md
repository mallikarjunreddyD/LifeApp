# Phase 7B workout record mapping

The local Room and IndexedDB editors store a session with its entries and
sets. The Kotlin projection converts a workout log to individually addressed
exercise definitions, routines, session headers, exercise entries and sets.
It reuses the portable workout fields. A first projection preserves UUIDs,
revisions, dates, workout measurements, parent IDs, and order. Reassembly
validates relationships and rejects missing parents and duplicate identities.

A change to a set changes its record. The diff ignores aggregate metadata
bumps when the parent fields are identical. When a parent itself changes,
the diff retains that record and its metadata. Writes list parents before
children. Removals list children before parents so a future operation journal
can issue tombstones in a safe order. Routine exercise lists remain ordered
inside the routine record in this increment.

This mapping is the first Phase 7B increment. Both clients still save
workouts locally. The transport, transactional outbox, conflict retention,
first-sync review, and cross-device acceptance are pending. Reassembling
independent set edits in tests verifies the record boundaries. Same-set
conflicts still require an operation history and an explicit resolution path.
