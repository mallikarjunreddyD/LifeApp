#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
root=$PWD
work=$(mktemp -d)
container=
server_pid=
cleanup() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  if [ -n "$container" ]; then docker stop "$container" >/dev/null; fi
  rm -rf "$work"
}
trap cleanup EXIT
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=127.0.0.1' \
  -addext 'subjectAltName=IP:127.0.0.1' -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null
image=postgres@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b
container=$(docker run --detach --rm -p 127.0.0.1::5432 --tmpfs /var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=disposable-only -e POSTGRES_DB=lifeapp_browser "$image")
for i in $(seq 1 35); do
  if docker exec "$container" pg_isready -q -h 127.0.0.1 -U postgres -d lifeapp_browser; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d lifeapp_browser
db_port=$(docker port "$container" 5432/tcp | cut -d: -f2)
api_port=$(python3 - <<'PYPORT'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PYPORT
)
printf '%s' 'disposable-only' > "$work/db-password"
export LIFEAPP_DB_URL="jdbc:postgresql://127.0.0.1:$db_port/lifeapp_browser"
export LIFEAPP_DB_USER=postgres LIFEAPP_DB_PASSWORD_FILE="$work/db-password"
export LIFEAPP_HTTPS_ORIGIN=https://127.0.0.1:4186 LIFEAPP_PORT="$api_port"
export LIFEAPP_WEB_API="http://127.0.0.1:$api_port"
export LIFEAPP_WEB_TLS_KEY="$work/key.pem" LIFEAPP_WEB_TLS_CERT="$work/cert.pem"

./gradlew :apps:server:installDist --console=plain
server="$root/apps/server/build/install/server/bin/server"
# The disposable owner uses the same interactive command as a real instance.
printf '%s\n' 'browser-test-owner' 'disposable browser test password' |
  script -qefc "\"$server\" init-owner" /dev/null > "$work/owner.log"
"$server" > "$work/server.log" 2>&1 &
server_pid=$!
for i in $(seq 1 20); do
  if curl --silent --fail --max-time 3 "$LIFEAPP_WEB_API/ready" > "$work/ready.json"; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$work/server.log"; exit 1; fi
  sleep 1
done
test "$(cat "$work/ready.json")" = '{"status":"ready"}'
cd "$root/apps/web"
npm run build
npm run test:browser -- "$@"
