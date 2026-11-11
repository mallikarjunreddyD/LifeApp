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
