# Local backup and restore

## Create and check a backup

1. Finish writing and resolve any visible save/load error. Open Settings → Backup &
   Restore → Create full local backup. Choose a local destination in the system picker.
2. Operations lock UI edits, wait up to30 seconds for all four editor queues, reject
   failed/unsaved state and capture one consistent Room read transaction. Initialization/
   migration finishes before starting that transaction to avoid the legacy read-open stall.
3. Data and LJPT files are generated off the main thread. The archive is read back and
   completely validated, then the chosen document is written, reread and verified by
   length/SHA-256. Only then does the app report Backup created and verified.
4. Keep copies outside app-private storage. Backups are not encrypted. A file picker
   can expose online providers; select a local provider for entirely offline operation.

Active writing ends when leaving an editor; native release commits before unpinning.
Pending edits are never silently left out. A failed/stuck queue blocks backup/export
until repaired or retried. Process death before commit can still lose pending edits;
backup is not a substitute for waiting for local-save success.

## Restore

1. Settings → Restore from file selects an archive through ACTION_OPEN_DOCUMENT.
   It is copied to a bounded private staging file and validated before any database
   write. Invalid/corrupt/unsupported backups show an error and leave current data intact.
2. Review creation/app version and record counts. A clean database requires explicit
   Restore all data confirmation. A populated database instead shows a replacement
   warning and requires choosing a separate pre-restore recovery ZIP destination.
3. Confirm Replace existing data. All editor queues must drain again. The input
   archive is revalidated. Current data is captured and its recovery ZIP generated,
   validated, written to the chosen recovery URI and reread/verified. Failure here
   aborts replacement without changing current records. Never choose the input backup
   as the recovery destination; ACTION_CREATE_DOCUMENT normally creates a separate file.
4. Old editor scopes/readers are cancelled only after recovery succeeds. All deletes
   and inserts, in FK order, occur in one immediate SQLite transaction. Exact original
   UUIDs, timestamps, revisions, archived flags, ordering and LJPT bytes are restored.
5. On success, all four editor caches/scopes are replaced and the Activity/ViewModel
   is recreated. Old queues cannot overwrite restored records. On transactional failure,
   SQLite rolls back and editor caches are rebuilt against the original database.

No merging, deduplication by title, or cloud conflict resolution is performed.
Replacing data is an explicitly confirmed user operation, not an automatic side effect
of selecting a file. Existing data is never deleted by archive validation.

## Interruption and recovery

An exception or cancellation during replacement rolls back its transaction. Process
termination uses SQLite transaction recovery: reopening should expose a complete
previous or committed new state, not a sequence of partially imported sections.
No guarantee is made about unfinished external-provider writes or device power loss
beyond SQLite/provider durability. A verified pre-restore ZIP remains outside the
application database for recovery; keep it until all restored modules are checked.

To recover, select that recovery ZIP through Restore from file, validate its summary
and follow the same replacement/recovery safeguards. Never uninstall to fix a failed
restore: uninstalling can erase app-private data. Maintain a consistent debug signing
key for in-place APK updates. If export fails, the destination may be partial; discard
that file and retry. A failed restore does not make its input a valid backup.

Tests inject database write failures and cancellation after earlier replacement writes
and verify every original record survives. Real-file round trips compare all metadata
and exact point bytes and then erase/reinsert restored notebook ink to prove editability.
Physical Xiaomi testing and third-party document-provider durability remain pending.

## Phase3.1 updates and archived journal pages

The existing Phase3 app can create a complete verified backup before installing an
update. Preserve that file off the tablet. Phase3.1 accepts original schema4 archives
with all journal pages active, and writes schema5 with explicit archived page flags.
Both active and deleted/recoverable pages retain their vectors/order/metadata through
backup and restore. New schema5 archives require Phase3.1 or later.

Delete Page is reversible visibility, not stroke deletion. It requires confirmation;
the last active page is protected in both editor and repository. Pending edits remain
FIFO and target original page UUIDs; late gestures cannot reassign or unarchive pages.
Restore Pages returns them to normal navigation at original order. Page/entry updated
metadata advances; creation times and immutable stroke metadata are preserved.

Before upgrading the installed app, follow safe-android-upgrade.md: compare signing
certificates, use the original private key and install with -r. CI debug signatures
may differ; never uninstall or erase data to resolve a mismatch. Tablet certificate
compatibility cannot be inferred from successful compilation or emulator tests.
