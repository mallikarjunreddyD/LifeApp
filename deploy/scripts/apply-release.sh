#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
unset LIFEAPP_API_IMAGE LIFEAPP_WEB_IMAGE LIFEAPP_RELEASE_COMMIT
exec 9>.release.lock
flock -n 9 || { echo 'A LifeApp rollout is already running.' >&2; exit 1; }

validate() {
  local commit=$1 api=$2 web=$3
  [[ $commit =~ ^[a-f0-9]{40}$ &&
     $api =~ ^ghcr\.io/mallikarjunreddyd/lifeapp-api@sha256:[a-f0-9]{64}$ &&
     $web =~ ^ghcr\.io/mallikarjunreddyd/lifeapp-web@sha256:[a-f0-9]{64}$ ]]
}

candidate=$(mktemp .release.XXXXXX)
trap 'rm -f "$candidate"' EXIT
if [[ ${1:-} == --rollback && $# == 1 ]]; then
  mapfile -t previous < previous.env
  commit=${previous[0]#LIFEAPP_RELEASE_COMMIT=}
  api=${previous[1]#LIFEAPP_API_IMAGE=}
  web=${previous[2]#LIFEAPP_WEB_IMAGE=}
elif [[ $# == 3 ]]; then
  commit=$1 api=$2 web=$3
else
  echo 'Usage: apply-release.sh COMMIT API_DIGEST_REFERENCE WEB_DIGEST_REFERENCE | --rollback' >&2
  exit 2
fi
validate "$commit" "$api" "$web" || { echo 'Invalid immutable release references.' >&2; exit 2; }
printf 'LIFEAPP_RELEASE_COMMIT=%s\nLIFEAPP_API_IMAGE=%s\nLIFEAPP_WEB_IMAGE=%s\n' "$commit" "$api" "$web" > "$candidate"

compose() {
  local release=$1
  shift
  docker compose -p lifeapp --env-file .env --env-file "$release" -f compose.yaml "$@"
}
compose "$candidate" config --quiet
compose "$candidate" pull
for image in "$api" "$web"; do
  actual=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")
  [[ $actual == "$commit" ]] || { echo 'Image revision does not match requested commit.' >&2; exit 1; }
done
if ! compose "$candidate" up -d --wait --wait-timeout 120; then
  echo 'Candidate failed readiness.' >&2
  if [[ -f release.env ]]; then
    echo 'Reapplying the last verified image references.' >&2
    compose release.env up -d --wait --wait-timeout 120
  fi
  exit 1
fi
if [[ -f release.env ]] && ! cmp -s release.env "$candidate"; then
  cp release.env previous.env
fi
mv "$candidate" release.env
printf 'LifeApp ready at commit %s\nAPI %s\nWeb %s\n' "$commit" "$api" "$web"
