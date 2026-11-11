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

Still pending are the atomic commit and authenticated download paths,
scheduled cleanup for inactive datasets, durable client operations, initial
migration review, conflict resolution and cross-device validation for each
module. Journal, Planner, Habits and Notebook continue to save locally.

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
