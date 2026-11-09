#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
image=postgres@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b
container=$(docker run --detach --rm -p 127.0.0.1::5432 --tmpfs /var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=disposable-only -e POSTGRES_DB=lifeapp_test "$image")
work=$(mktemp -d)
server_pid=
cleanup() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  docker stop "$container" >/dev/null
  rm -rf "$work"
}
trap cleanup EXIT
for i in $(seq 1 35); do
  if docker exec "$container" pg_isready -q -h 127.0.0.1 -U postgres -d lifeapp_test; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d lifeapp_test
port=$(docker port "$container" 5432/tcp | cut -d: -f2)
export LIFEAPP_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$port/lifeapp_test"
export LIFEAPP_TEST_DB_USER=postgres LIFEAPP_TEST_DB_PASSWORD=disposable-only
./gradlew :apps:server:test :apps:server:installDist --console=plain

umask 077
printf '%s' "$LIFEAPP_TEST_DB_PASSWORD" > "$work/db-password"
api_port=$(python3 - <<'PYPORT'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PYPORT
)
LIFEAPP_DB_URL="$LIFEAPP_TEST_DB_URL" LIFEAPP_DB_USER="$LIFEAPP_TEST_DB_USER"   LIFEAPP_DB_PASSWORD_FILE="$work/db-password" LIFEAPP_HTTPS_ORIGIN=https://lifeapp.example.test   LIFEAPP_PORT="$api_port" apps/server/build/install/server/bin/server > "$work/server.log" 2>&1 &
server_pid=$!
base="http://127.0.0.1:$api_port"
for i in $(seq 1 20); do
  if curl --silent --fail --max-time 3 "$base/ready" > "$work/ready.json"; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$work/server.log"; exit 1; fi
  sleep 1
done
test "$(cat "$work/ready.json")" = '{"status":"ready"}'
test "$(curl --silent --fail --max-time 3 "$base/health")" = '{"status":"ok"}'
test "$(curl --silent --max-time 3 --output /dev/null --write-out '%{http_code}' "$base/v1/todos")" = 401
printf '%s
' 'Packaged Netty server passed localhost health, readiness and unauthorized-access checks.'
