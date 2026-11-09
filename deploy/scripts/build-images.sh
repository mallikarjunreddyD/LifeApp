#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${LIFEAPP_API_IMAGE:?Set the API image tag}"
: "${LIFEAPP_WEB_IMAGE:?Set the web image tag}"
revision=$(git rev-parse HEAD)
context=$(mktemp -d)
trap 'rm -rf "$context"' EXIT
./gradlew :apps:server:installDist --console=plain
(cd apps/web && npm ci && npm run build)
cp -a apps/server/build/install/server "$context/server"
cp -a apps/web/dist "$context/web"
cp deploy/images/nginx.conf "$context/"
docker build --label "org.opencontainers.image.revision=$revision" \
  --label "org.opencontainers.image.source=https://github.com/mallikarjunreddyD/LifeApp" \
  --tag "$LIFEAPP_API_IMAGE" --file deploy/images/api.Dockerfile "$context"
docker build --label "org.opencontainers.image.revision=$revision" \
  --label "org.opencontainers.image.source=https://github.com/mallikarjunreddyD/LifeApp" \
  --tag "$LIFEAPP_WEB_IMAGE" --file deploy/images/web.Dockerfile "$context"
