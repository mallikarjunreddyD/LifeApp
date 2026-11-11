#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
project="lifeapp-smoke-${work##*.}"
project=${project,,}
port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
export LIFEAPP_LOOPBACK_PORT=$port LIFEAPP_HTTPS_ORIGIN="https://lifeapp.example.test"
export LIFEAPP_DB_PASSWORD_PATH="$work/db-password" LIFEAPP_DB_ADMIN_PASSWORD_PATH="$work/admin-password"
openssl rand -hex 32 > "$LIFEAPP_DB_PASSWORD_PATH"
openssl rand -hex 32 > "$LIFEAPP_DB_ADMIN_PASSWORD_PATH"
# The parent directory is mode 0700. The two container UIDs need file access.
chmod 644 "$LIFEAPP_DB_PASSWORD_PATH" "$LIFEAPP_DB_ADMIN_PASSWORD_PATH"
compose=(docker compose -p "$project" -f deploy/compose.yaml)
cleanup() {
  status=$?
  if ((status != 0)); then "${compose[@]}" ps -a; "${compose[@]}" logs --tail=50; fi
  if [[ -n ${restore_project:-} ]]; then
    docker compose -p "$restore_project" -f deploy/compose.yaml down --volumes >/dev/null
  fi
  "${compose[@]}" down --volumes >/dev/null
  rm -rf "$work"
}
trap cleanup EXIT
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait --wait-timeout 120
for service in db api; do
  container=$("${compose[@]}" ps -q "$service")
  test "$(docker inspect --format '{{len .HostConfig.PortBindings}}' "$container")" = 0
done
test "$("${compose[@]}" port web 8080)" = "127.0.0.1:$port"
test "$("${compose[@]}" exec -T api id -u)" = 10001
test "$("${compose[@]}" exec -T web id -u)" = 101
test "$("${compose[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT rolsuper FROM pg_roles WHERE rolname='lifeapp'")" = f
python3 deploy/scripts/smoke-owner.py "${compose[@]}" exec api /opt/lifeapp/bin/server init-owner
python3 deploy/scripts/smoke-http.py seed
"${compose[@]}" up -d --force-recreate --wait --wait-timeout 120
python3 deploy/scripts/smoke-http.py verify
echo 'Compose passed loopback isolation, authentication, PWA assets and PostgreSQL durability across recreation.'
if [[ ${LIFEAPP_SMOKE_RESTORE:-0} == 1 ]]; then
  restore_project="lifeapp-restore-${project#lifeapp-smoke-}"
  age-keygen -o "$work/identity" >/dev/null 2>&1
  age-keygen -y "$work/identity" > "$work/recipients"
  epoch=$("${compose[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT id FROM recovery_epochs WHERE active")
  python3 deploy/scripts/smoke-http.py hold "$work/held-token"
  "${compose[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -v ON_ERROR_STOP=1 < deploy/scripts/smoke-workout.sql
  mkdir -m 700 "$work/archives" "$work/remote" "$work/recovery"
  printf 'LIFEAPP_RELEASE_COMMIT=%s\n' "$(git rev-parse HEAD)" > "$work/release.env"
  touch "$work/rclone.conf"
  export LIFEAPP_BACKUP_PROJECT="$project" LIFEAPP_BACKUP_RELEASE_FILE="$work/release.env"
  export LIFEAPP_BACKUP_RCLONE_CONFIG="${LIFEAPP_DRILL_RCLONE_CONFIG:-$work/rclone.conf}"
  export LIFEAPP_BACKUP_DRIVE_REMOTE="$work/remote"
  if [[ -n ${LIFEAPP_DRILL_DRIVE_FOLDER:-} ]]; then
    : "${LIFEAPP_DRILL_RCLONE_CONFIG:?Set the protected configuration for the disposable Drive drill}"
    LIFEAPP_BACKUP_DRIVE_REMOTE="${LIFEAPP_DRILL_DRIVE_FOLDER%/}/$project"
  fi
  export LIFEAPP_BACKUP_RECIPIENTS="$work/recipients" LIFEAPP_BACKUP_DIRECTORY="$work/archives"
  deploy/backup/upload-drive.sh
  cp "$work/archives/verified.sha256" "$work/recovery/trusted.sha256"
  read -r digest name < "$work/recovery/trusted.sha256"
  download="$PWD/deploy/backup/download-verified.sh"
  (cd "$work/recovery" && "$download" "${LIFEAPP_DRILL_DOWNLOAD_RCLONE_CONFIG:-$LIFEAPP_BACKUP_RCLONE_CONFIG}" \
    "$LIFEAPP_BACKUP_DRIVE_REMOTE" trusted.sha256 "$name")
  # A failed transfer must leave the earlier verified object and ledger entry usable.
  if LIFEAPP_BACKUP_DRIVE_REMOTE=":lifeapp-invalid-backend:$work/remote" deploy/backup/upload-drive.sh; then
    echo 'Expected the deliberately invalid upload to fail.' >&2
    exit 1
  fi
  cmp "$work/recovery/trusted.sha256" "$work/archives/verified.sha256"
  test "$("${compose[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT state FROM backup_jobs ORDER BY started_at DESC LIMIT 1")" = failed
  echo 'Verified an independent download and preserved the trusted digest after an upload failure.'
  restore=(docker compose -p "$restore_project" -f deploy/compose.yaml)
  "${restore[@]}" up -d --wait --wait-timeout 90 db
  python3 deploy/backup/database-backup.py --project "$restore_project" restore \
    --identity "$work/identity" --input "$work/recovery/$name" --sha256 "$digest"
  test "$("${restore[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT count(*) FROM sessions")" = 0
  test "$("${restore[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT count(*) FROM staged_ink")" = 0
  test "$("${restore[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT id FROM recovery_epochs WHERE active")" != "$epoch"
  LIFEAPP_LOOPBACK_PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
  export LIFEAPP_LOOPBACK_PORT
  "${restore[@]}" up -d --wait --wait-timeout 120
  LIFEAPP_OLD_EPOCH="$epoch" LIFEAPP_SMOKE_HELD_TOKEN="$work/held-token" python3 deploy/scripts/smoke-http.py verify
  echo 'Clean Compose restore passed readiness, session revocation, new epoch and restored To-do checks.'
fi
