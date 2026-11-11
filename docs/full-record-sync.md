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
