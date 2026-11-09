#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${LIFEAPP_BACKUP_RCLONE_CONFIG:?Set a protected rclone config path}"
: "${LIFEAPP_BACKUP_DRIVE_REMOTE:?Set a dedicated Drive destination such as lifeapp-drive:LifeApp}"
: "${LIFEAPP_BACKUP_RECIPIENTS:?Set an age recipients file}"
: "${LIFEAPP_BACKUP_DIRECTORY:?Set a private backup directory}"
cd "$(dirname "$0")/.."
exec 9>"$LIFEAPP_BACKUP_DIRECTORY/.lock"
flock -n 9 || { echo 'A backup is already running.' >&2; exit 1; }
exec 8>.release.lock
flock -s -n 8 || { echo 'A rollout is running. Retry after deployment.' >&2; exit 1; }
commit=$(sed -n 's/^LIFEAPP_RELEASE_COMMIT=//p' release.env)
name="lifeapp-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 6).tar.age"
archive="$LIFEAPP_BACKUP_DIRECTORY/$name"
destination="${LIFEAPP_BACKUP_DRIVE_REMOTE%/}/$name"
job=$(python3 -c 'from uuid import uuid4; print(uuid4())')
python3 backup/database-backup.py report --id "$job" --state running
failed() {
  status=$?
  if ((status != 0)); then
    python3 backup/database-backup.py report --id "$job" --state failed || true
  fi
}
trap failed EXIT
python3 backup/database-backup.py capture --commit "$commit" --recipients "$LIFEAPP_BACKUP_RECIPIENTS" --output "$archive"
rclone=(rclone --config "$LIFEAPP_BACKUP_RCLONE_CONFIG")
"${rclone[@]}" copyto --immutable "$archive" "$destination"
local_hash=$(sha256sum "$archive" | cut -d ' ' -f 1)
remote_hash=$("${rclone[@]}" cat "$destination" | sha256sum | cut -d ' ' -f 1)
[[ "$local_hash" == "$remote_hash" ]] || { echo 'Uploaded object verification failed.' >&2; exit 1; }
printf '%s  %s\n' "$local_hash" "$name" >> "$LIFEAPP_BACKUP_DIRECTORY/verified.sha256"
python3 backup/database-backup.py report --id "$job" --state verified
printf 'Drive object verified: %s\n' "$name"
