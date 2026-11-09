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
  job=$(python3 -c 'from uuid import uuid4; print(uuid4())')
  python3 deploy/backup/database-backup.py --project "$project" report --id "$job" --state running
  python3 deploy/backup/database-backup.py --project "$project" report --id "$job" --state verified
  python3 deploy/backup/database-backup.py --project "$project" capture \
    --commit "$(git rev-parse HEAD)" --recipients "$work/recipients" --output "$work/backup.age"
  restore=(docker compose -p "$restore_project" -f deploy/compose.yaml)
  "${restore[@]}" up -d --wait --wait-timeout 90 db
  python3 deploy/backup/database-backup.py --project "$restore_project" restore \
    --identity "$work/identity" --input "$work/backup.age" \
    --sha256 "$(sha256sum "$work/backup.age" | cut -d ' ' -f 1)"
  test "$("${restore[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT count(*) FROM sessions")" = 0
  test "$("${restore[@]}" exec -T db psql -U lifeapp_admin -d lifeapp -Atc "SELECT id FROM recovery_epochs WHERE active")" != "$epoch"
  LIFEAPP_LOOPBACK_PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
  export LIFEAPP_LOOPBACK_PORT
  "${restore[@]}" up -d --wait --wait-timeout 120
  LIFEAPP_OLD_EPOCH="$epoch" LIFEAPP_SMOKE_HELD_TOKEN="$work/held-token" python3 deploy/scripts/smoke-http.py verify
  echo 'Clean Compose restore passed readiness, session revocation, new epoch and restored To-do checks.'
fi
