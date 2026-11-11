# Google Drive recovery acceptance

Run this drill from a reviewed checkout on an isolated Docker host. It creates
two disposable Compose projects and seeds a test owner, a To-do operation and
a workout with a recorded set. The recovery target uses a new volume and a
different loopback port. The script removes those test projects afterward.

Install Docker Compose, Python 3, OpenSSL, age and rclone. Prepare the API and
web images from the same checkout with the repository build tools.

```sh
export LIFEAPP_API_IMAGE=lifeapp-api:recovery-drill
export LIFEAPP_WEB_IMAGE=lifeapp-web:recovery-drill
deploy/scripts/build-images.sh
LIFEAPP_SMOKE_RESTORE=1 deploy/scripts/smoke.sh
```

The default command uses rclone's local backend. It exercises the uploader,
read-back verification, independent download, failed-upload reporting and
clean restore. This validates the implementation in isolation.

For a live provider drill, configure a dedicated rclone Google Drive remote
with `drive.file` scope using the operator procedure in
[server backup and recovery](server-backup-design.md). Use an absolute path to
the protected config. Use a disposable Drive folder under the remote. The
script creates a unique child folder for each run. An optional separate
download configuration can exercise a second client with access to the same
folder.

```sh
export LIFEAPP_DRILL_RCLONE_CONFIG=/protected/drill-rclone.conf
export LIFEAPP_DRILL_DRIVE_FOLDER=lifeapp-drill:RecoveryDrills
LIFEAPP_SMOKE_RESTORE=1 deploy/scripts/smoke.sh
```

Record the run date and observed results. Verify the restored To-do, workout
set ID, recorded LB weight and repetition count, old-session revocation and
old-epoch rejection. The disposable fixture does not include server-side
handwriting. That record type remains local until its synchronization ships.

For a real recovery, take the SHA-256 ledger from independent trusted storage
and the age identity from the recovery machine. Use the following command in a
private recovery directory. It publishes the encrypted archive only after its
digest matches the trusted ledger. Then pass the same trusted digest to the
clean-project restore command.

```sh
/path/to/reviewed/deploy/backup/download-verified.sh \
  /protected/rclone.conf lifeapp-drive:LifeApp /protected/verified.sha256 \
  LIFEAPP_BACKUP_FILENAME
```

Live Drive OAuth, scheduled execution on the production VM, and live restore
acceptance are pending operator configuration and verification.
