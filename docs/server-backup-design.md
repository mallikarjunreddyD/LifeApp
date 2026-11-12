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

## Phase 6E operator procedure

The deployment bundle contains an age-encrypted PostgreSQL capture and a
clean-project restore tool. Capture exports one repeatable-read snapshot for
both the manifest and the dump. The manifest records active datasets and
epochs, commit counters, schema rank, counts, server commit and dump checksum.
Current synchronized data resides in PostgreSQL. Android handwriting and
other local modules still require the separate Android backup.

Generate the age identity on a recovery machine. Copy only the public
recipients file to the VM and retain the private identity independently.
Keep the VM backup directory private. A verified checksum ledger belongs
in a separate protected recovery location, independent of Drive. A digest
computed from the downloaded file alone does not establish provenance.

For off-site upload, create a dedicated Google Drive folder and rclone
remote. Use an OAuth client in production mode with renewable access,
request the `drive.file` scope, and authorize the dedicated remote under
the intended account. Configure and test it interactively with disposable
data before enabling the timer. Store the rclone config with mode 0600.
Testing-mode OAuth grants can expire. [rclone Drive setup](https://rclone.org/drive/)

Set these values in the service's private `/srv/lifeapp/backup.env` file:

```dotenv
LIFEAPP_BACKUP_RCLONE_CONFIG=/srv/lifeapp/secrets/rclone.conf
LIFEAPP_BACKUP_DRIVE_REMOTE=lifeapp-drive:LifeApp
LIFEAPP_BACKUP_RECIPIENTS=/srv/lifeapp/secrets/age-recipients.txt
LIFEAPP_BACKUP_DIRECTORY=/srv/lifeapp/backups
```

After provisioning the `lifeapp-ops` account and installing `age` and
`rclone`, run the upload script once as that account. The upload uses a new
name, reads the encrypted bytes back and records their SHA-256 after
verification. Keep older copies. The supplied systemd timer runs daily at
03:00 UTC with jitter. Install the reviewed service and timer into
`/etc/systemd/system`, reload systemd and enable only the timer after the
manual check. Retention is opt-in via `LIFEAPP_BACKUP_PRUNE=1`. It keeps one
verified object per bucket for the latest seven days, four ISO weeks and
three months represented by backups, plus the two newest verified objects.
Override the bucket counts with `LIFEAPP_BACKUP_DAILY`,
`LIFEAPP_BACKUP_WEEKLY` and `LIFEAPP_BACKUP_MONTHLY`. The uploader only prunes
exact names found in the verified ledger after a new backup verifies. Local
archives and the ledger are retained. Drive OAuth, live retention and a
live restore drill require separate verification.

For restore, use a fresh isolated Compose project named
`lifeapp-restore-DRILL_ID` on a disposable VM or a separate reviewed
deployment directory. Configure independent password files, a test HTTPS
origin and a free loopback port there. Supply the image references recorded
for the backup's server commit. Start only PostgreSQL and import the
archive using the known digest from the trusted ledger.

```sh
project=lifeapp-restore-drill
docker compose -p "$project" --env-file .env --env-file release.env up -d --wait db
python3 backup/database-backup.py --project "$project" restore \
  --identity /protected/age-identity.txt --input /protected/selected.tar.age \
  --sha256 EXPECTED_SHA256_FROM_TRUSTED_LEDGER
docker compose -p "$project" --env-file .env --env-file release.env up -d --wait
```

The restore rejects a populated database, validates the manifest against
the imported database, revokes sessions and creates new active recovery
epochs with the restored causal histories. Check readiness and task
visibility with a fresh login. Old client checkpoints must remain blocked
until separately reconciled. Keep the original server and backups intact
through the drill.

Phase 7G introduces an independent managed backup scope and a twice-daily
Asia/Kolkata timer. The Phase 6E instructions above describe the legacy
manual uploader. Use the [Phase 7G operator notes](../deploy/README.md)
for the new scheduler and retention.
