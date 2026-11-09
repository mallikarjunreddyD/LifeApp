# Phase 4 data exchange and Drive feasibility

This document records the Phase 4 design. Phase 5 replaces its full-list transport
with the [server protocol](sync-protocol-v1.md). Drive remains a backup destination.

## Verified scope

The shared synchronization core covers To-do records. It runs between independent
Room databases using an injected append-only provider. The Android application
records local To-do changes durably and offers encrypted Drive backups separately.
Live operation synchronization is disabled. Journal, planner, habits and notebooks
remain locally editable and are included in backups. They do not emit operation
records. Fake-provider results establish To-do protocol behavior.

## Durable To-do outbox and conflicts

Schema 6 adds `todos`, `todo_operations` and `todo_sync_device`. Each user change
updates the projection and inserts an immutable pending operation in one immediate
transaction. This includes create, edit, complete, reopen and tombstone. The stable
device UUID and incremented local sequence are committed in the same transaction.
Only acknowledgments flip the pending flag. They compare the full operation before
updating. An already committed remote operation also serves as its own acknowledgment.

The shared JSON codec identifies `LifeAppOperation`, protocol version 1, operation
UUID, device UUID, local sequence, record type and UUID, put/delete, causal parent
operation IDs and the complete task record. A task has a calendar deadline, UTC
creation/edit/completion instants, revision and a deletion flag. Creation and edit
times are metadata. The causal graph determines ordering. Wire bytes must be
authenticated by the eventual transport before decoding. The fake provider serializes
plain JSON in memory and never transfers it across a network.

Replay validates parent ownership and sequence, orders parents before children,
accepts equivalent duplicates and rejects inconsistent operation identities,
cycles and missing dependencies. Receipt, projection and pending acknowledgment
are transactional. Independent record edits converge. Concurrent heads remain in
the operation history. The current projection uses an operation ID tie-break and
prefers a tombstone when a delete conflicts with an edit. The shared repository
can inspect heads and resolve by creating a successor that names every reviewed
head. If the heads changed in the meantime, the decision is rejected. A deleted
identity cannot be revived by a stale or descendant edit. The unresolved version
is still accessible to the resolver. Conflict review is an API exercised by tests.

The coordinator downloads once per invocation, replays then uploads pending
operations. It leaves unacknowledged changes on disk if a request fails. This core
uses a full bounded-by-provider listing and refetch after interruption. It has no
live job scheduler, incremental checkpoint, or cloud account namespace. History
is retained. Full local restore intentionally replaces the local task projection
and reseeds the independent local outbox. Operation histories, account state and
conflicts are outside the portable backup format. Cloud operation replication
must define restore epochs before enabling synchronization on this database.

## Drive REST v3 assessment

Drive can carry an append-only file protocol. Use appDataFolder with the
`drive.appdata` scope. A stable operation ID is necessary because Drive filenames
are not unique. Reserve server file IDs with files.generateIds and persist the
mapping for retries. A repeat create with a committed generated ID can return
409. Read and authenticate the existing payload before accepting that acknowledgment.
Never use listing order or mutable file revisions as a causal clock. Concurrent
writers create independent immutable files. Backups use the same append-only
principle with their own file type and explicit restore workflow.

Use all pages from files.list during initialization. For future incremental
replication, obtain the changes token before listing to avoid missing writes
during initialization, then replay the change feed and persist the new token only
after durable processing. Duplicates must be harmless across the listing and
change feed. A reinstated client needs the same Google account, OAuth project and
passphrase. A complete operation history would allow reconstruction. An account
switch must select a distinct local cloud namespace before uploading. Never
reuse one account’s pending journal or checkpoint in another account.

Quotas, interrupted transfers, throttling and manually removed app data are
real failure modes. Resumable creates and read-back verification are implemented
for backups. Retry automated operations with backoff and jitter when the live
transport is introduced. Treat incomplete listings and missing dependencies as
failures. Authenticate payloads before any changes. Hidden app data cannot be
shared or browsed through Drive UI and can be removed by the user. Retain local
backups independently. History grows until there is a proven retention policy.
Compaction would require portable checkpoints and a way to keep offline clients
safe. Current code deletes no remote files.

Before enabling live sync, implement encrypted operation transport and durable
checkpoints per account, startup/download and connectivity-triggered upload
work, explicit conflict review, and per-record reconciliation for existing modules.
The existing schema has unique dates/months and ordered parent/child collections.
Independent creation of the same month, page ordering, child ownership and
simultaneous stroke add/remove need defined merge rules and migrations. Replacing
whole notebooks or merging raw rows would risk losing data. Preserve stroke IDs
and explicit remove operations. Confirm interoperability with two devices against
one authenticated test project before enabling the feature.

- [Application data folder](https://developers.google.com/workspace/drive/api/guides/appdata)
- [Create files and generated IDs](https://developers.google.com/workspace/drive/api/guides/create-file)
- [Change feed](https://developers.google.com/workspace/drive/api/guides/manage-changes)
- [Upload media](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Quotas](https://developers.google.com/workspace/drive/api/guides/limits)
