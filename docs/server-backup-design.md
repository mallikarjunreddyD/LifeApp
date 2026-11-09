# Future server backup and recovery

Server disaster recovery is separate from the Android backup and from the operation
feed. Use a versioned encrypted bundle containing a PostgreSQL custom-format dump,
a manifest, and application-owned objects such as external ink or attachments if
stored outside PostgreSQL. Preserve database migrations, application build
identifiers and infrastructure configuration needed to recreate the VM. Store
deployment secrets in a separate recovery channel.

Acquire a consistent application checkpoint. Quiesce mutations and object garbage
collection during capture, or use a repeatable-read exported database snapshot plus
immutable content-addressed objects. Record exactly which objects the snapshot
references. Verify object availability and SHA-256 before completing the bundle.
A database dump alone is insufficient when rows point to external objects.

The manifest should include format version, server build, schema version,
checkpoint, UTC creation time, file list, sizes, digests and encryption envelope
version. Encrypt and authenticate the whole bundle with an established format
such as age recipient encryption. Store recovery private keys independently of
the VM and verify access from a clean machine. Limit uploader access to its
backup files. Upload new immutable Drive objects, confirm stored integrity and
retain daily, weekly and monthly generations. Set the precise retention policy
after measuring size and recovery requirements. Preserve the previous verified
backup after any failure. Monitor missing or stale verified backups.

Restore into an isolated clean Ubuntu environment. Retrieve and authenticate the
bundle, verify the manifest, import the matching database and restore referenced
objects. Check counts, identities, LJPT bytes, ownership, migrations and readiness.
Rotate sessions after a loss and publish a new recovery epoch before reconnecting
clients. Keep old client outboxes for review. Test restores regularly and measure
recovery point and time. The deployment, scheduler and upload automation are
Phase 6 work.

- [PostgreSQL dump documentation](https://www.postgresql.org/docs/current/app-pgdump.html)
