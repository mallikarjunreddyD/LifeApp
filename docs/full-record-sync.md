# Phase 7E editable record synchronization

The first increment adds authenticated staging for original LJPT v1 stroke
bytes. Uploads carry a stable UUID, dataset and recovery epoch, and SHA-256.
The server checks authentication, browser CSRF protection, scope, byte length,
checksum and the existing point codec before storing the original bytes.
An identical retry succeeds within the staging period. Reusing an upload ID
with different content returns a conflict.

Each blob is limited to 20,000,012 bytes, the existing LJPT v1 maximum.
A dataset can stage up to 256 blobs or 64 MiB at once. A valid upload
removes expired and superseded-epoch staging rows for its dataset. A clean
database recovery discards temporary uploads when it rotates the epoch.
The staging endpoint provides no download or record visibility.

The next increment added the atomic commit and authenticated download paths described below.

## Journal stroke commit and download

`POST /v1/journal/ink/commit` accepts an existing portable journal entry with
one active page, a portable stroke header, the staging upload UUID and SHA-256,
and the authenticated dataset and recovery epoch. The stroke header contains
metadata, surface UUID, order, brush, ARGB, width and point format version.
The original bytes travel only through the binary endpoint.

The transaction checks page ownership and logical bounds, writes the entry
and stroke together, and consumes the staging row. A replay with the same
entry, stroke and digest succeeds after staging is consumed. A reused UUID,
date, page position or upload with different content returns a conflict.
The endpoint leaves both existing data and staged bytes intact when validation
fails. This increment supports one active page per imported entry and
additional strokes on that page. Entry edits and additional pages need
separate conflict rules.

`GET /v1/journal/ink/{strokeId}` returns the original bytes and their SHA-256
for a committed stroke in the authenticated dataset. The server stores the
committed records independently of the recovery epoch. Encrypted PostgreSQL
backups retain them, and clean recovery discards temporary uploads. Existing
Journal, Planner, Habits and Notebook editors continue to save locally.

## Read-only journal preview

The PWA Journal tab lists committed entries from the authenticated dataset.
Open a date to preview the imported page. The API pages through stroke
headers in their stored order. Each request checks the dataset and recovery
epoch. The browser verifies the SHA-256 of the downloaded LJPT v1 bytes,
then draws the original logical coordinates on a clipped, proportional
canvas. The canvas uses a constant-width path for each stroke and a plain
paper background. The preview retains the original stroke color and
opacity. Rendering does not convert or write back any stroke data.

The preview requires a connection. Journal data remains in memory while
the tab is open and is removed when the tab closes, the app goes offline
or the active scope changes. The API marks responses `no-store` and the
PWA service worker excludes API responses. A preview is limited to 64 MiB
of source strokes and 10,000 strokes per page. An incomplete or invalid
download shows an error and retry control.

The read endpoints reuse the existing PostgreSQL tables. They require no
Room or PostgreSQL migration and the existing recovery format already
includes the committed data. The Journal tab displays only what has been
committed through the server import endpoint. Android has no journal
upload or replay UI yet. Durable editor operations, a backup-verified
first sync, migration inventory and conflict review, multi-page and archive
operations, stroke removal, inactive staging cleanup, and Planner, Habits
and Notebook synchronization remain open. Full cross-device acceptance
requires those features and testing with the Android client.

## Android first-sync journal inventory

On Android, sign in under **Settings → LifeApp Server** and choose
**Back up and review journal inventory**. Select a separate local ZIP
destination. LifeApp drains queued editor writes, captures the committed
snapshot, validates the full backup and reads the destination back to verify
its bytes. It then reads the authenticated server's paged journal inventory
for the selected HTTPS origin, dataset and recovery epoch.

The review shows local journal entry, page and stroke totals, committed
server entry totals, entries present on one side only, overlapping dates,
and dates affected by a reused entry or page ID. The local totals include
archived pages. An overlap calls for review even when entry IDs match,
because this inventory does not compare stroke bytes. The review is
preliminary because the server list can change during paging.

This screen performs only authenticated reads. It does not bind journals
to the dataset or apply changes. The next import step still needs to
revalidate the inventory at commit, preserve conflicting variants, compare
stroke IDs and digests, and apply approved entries atomically. Planner,
Habit Tracker and Notebook inventory and synchronization are still pending.

## Versioned records on the server

The editable-record API stages a portable ZIP for one journal date, planner
month, habit month or notebook. The archive carries that record and its
children. The server validates its manifest, IDs, parent relationships,
checksums and LJPT points before staging. A ZIP can be at most 16 MiB.
The server saves the original bytes and checks the SHA-256 supplied by
the client. A separate commit publishes the version and consumes staging
in one PostgreSQL transaction. A retry with the same version ID, digest
and parents succeeds after commit.

A new version with a stale parent can coexist with another current
version. A resolution naming multiple parents must name every current
version. Earlier versions remain available in order through the scoped
version feed. The server rejects reuse of a UUID under a different owner
record, reuse of an immutable stroke with different bytes or metadata, and
crossing between the old single-page journal import and the new version
store. Earlier single-page journal imports can be moved into the version store
from the Journal tab.

The browser can preview a committed version from **Journal → Server record
versions**. It draws vector paths at their logical coordinates and clips
planner strokes to their original cells. Previews are online and read-only.
They use constant-width paths on a plain background, with a bounded
response of 8 MiB and at most 4 MiB of raw LJPT data. Version archives
remain downloadable even when a browser preview exceeds the limit.

Migration V6 adds the version store and upload expiry indexes. The
server purges expired staging in bounded batches. The clean database
restore script clears staged uploads, retains committed versions and
rotates the recovery epoch.

## First transfer between Android and the server

In **Settings → LifeApp Server**, choose **Back up and review record
changes**. After the separate backup is verified, LifeApp compares its
journal dates, planner months, habit months and notebook UUIDs with the
authenticated server inventory. The review lists overlaps and competing
server versions. Existing records on either side are skipped.

**Approve upload** saves each approved record archive and a stable version
UUID in a private, backup-excluded queue. The queue is bound to the selected
HTTPS origin, dataset and recovery epoch. Each archive is committed in its
own server transaction. If a matching key appears after review, that
upload stays queued for review. **Resume record uploads** resends the same
archive after interruption or a lost receipt. **Clear queued upload copies**
discards pending copies while retaining Android records, the independent
backup and any completed server uploads.

**Apply reviewed server records** verifies the chosen versions and each
archive checksum, then adds the batch in one Room transaction. The
transaction rejects all records in the batch if a local date, month or
record identity would collide. Existing records and To-do replay state
are preserved. The editor caches are reloaded after application.

The first transfer adds records. After a shared version has been
confirmed, the same review can also transfer updates. Complete cross-device acceptance before
calling the four modules synchronized.


## Resolve competing server versions

In the online PWA, open **Journal → Server record versions**, select a
record, and choose **Review current versions**. The review fetches the
complete current version set for that record, including versions outside
the visible page of history. Preview the variants, then choose **Keep
selected version** and confirm. The server checks the full reviewed set
under the dataset and recovery epoch lock. If another commit changed
the set, refresh the review before choosing again.

The resolution creates a new current version with the same archive
bytes and the reviewed variants as its parents. All earlier archives
remain accessible from the version history. If the browser loses the
response, **Retry resolution** reuses the same resolution UUID while
the review stays open. This updates server history. Android can
apply the selected server version through the record review workflow
when its local copy still matches a confirmed shared version.


## Move an earlier journal import into version history

In the online PWA, choose a date under **Earlier single-page journal
imports** and select **Move to server record versions**. Migration V7
adds a mapping from the original journal entry to its first archive
version. The server checks the current dataset and recovery epoch, verifies
each original stroke checksum, and compares the encoded LJPT bytes to the
stored bytes. The version, identity registration and mapping commit in one
transaction. Retrying returns the same version ID.

The original metadata, page and strokes remain stored. The earlier
journal list omits the migrated entry, and the original stroke download
and already committed upload retries continue to work. New edits to
the migrated date go through the versioned API. The archive retains the
entry, page and stroke UUIDs and original metadata. Entries beyond the
portable record limits or the 16 MiB archive limit remain in the earlier
store.


## Per-day habit checkboxes in the PWA

Open a current habit ledger under **Journal → Server record versions**.
Choose a day and toggle an active habit. The browser sends one habit ID,
date and checkbox value. The server publishes a new ledger version and
saves the operation receipt in one transaction. An interrupted response
can be retried with the same operation ID while the browser view stays open.

If the current ledger changed on other dates, the server applies the edit
to that ledger and keeps the independent changes. If the requested date
and habit also changed and the values disagree, the server retains two
current versions. Both include the other dates' latest changes. Resolve
the variants before making more checkbox edits. Completion identities
are retained on updates. An archived habit or a changed ledger identity
requires a fresh review.

The PWA checkbox editor requires a connection. Android keeps local
checkbox edits in Room offline. When a tracked ledger only changed
checkboxes, Android queues the changed dates as individual operations
with the original completion IDs and metadata.


## Reviewed Android updates

Android records the last confirmed server version and exact portable
record fingerprint after an upload or download. The fingerprints are
private and scoped to the HTTPS origin, dataset and recovery epoch.
For records transferred before version tracking was added, review can
establish a shared version when the local and current server contents
match exactly. Review skips overlaps that have no verified common version.

If Android edited a tracked record, approval queues its archive with
the confirmed parent version. The queue survives interruption. A newer
competing server version remains available for conflict review. If
only the server copy changed, approval applies that version after
checking the reviewed server version and verifying the Android record
again inside the Room transaction. A local change rejects the batch.
Unrelated records, To-do synchronization state and workouts are untouched.
Restoring a backup resets version tracking while preserving pending
upload copies.

Physical cross-device acceptance remains pending.


## Automatic Android transfer for tracked records

After confirming a shared version through the backup-verified review,
enable **Automatically sync records** in **Settings → LifeApp
Server**. The preference applies to that HTTPS origin, dataset and
recovery epoch. Android periodically checks committed Room records while
connected and saves upload copies before sending. It also checks for
server updates when the app opens. Automatic transfer covers tracked
records and new records created after automatic sync is enabled.
Existing untracked records are left for the backup-verified review.
Overlapping keys without a shared version are also left for review.

The upload pass can coalesce multiple committed edits into one record
version. Checkbox-only Android changes use individual date operations
with stable retry IDs. Mixed definition and completion edits can still
use a record archive. Independent checkbox-only archive branches can
merge without changing completion identities. Competing versions remain
available in the PWA review.

Before applying a server update, the foreground pass drains editor saves
and checks the exact reviewed local copy inside the Room transaction.
If the local record changed, it keeps that copy and retries on a later
pass. Android applies downloads while the app is open so it can safely
reload the editor caches. Signing out or restoring a local backup pauses automatic work and keeps
queued copies. A changed recovery epoch requires a fresh review.
