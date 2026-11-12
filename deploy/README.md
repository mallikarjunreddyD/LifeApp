# Deploy LifeApp on an Ubuntu VM

Use a dedicated directory such as `/srv/lifeapp`, Docker Engine 25+ and a
current Docker Compose v2 plugin. Install the reviewed `deploy/` directory
there. Use a deployment account that can run Docker. The Docker group grants host-level privileges. Keep the VM runner disconnected
from the public LifeApp repository.

## Prepare credentials and images

Copy `env.example` to `.env`. Set the canonical HTTPS origin and an available
loopback port. Use the origin you will keep for both Android and browser clients.
The two files below are separate secrets. On a rootful Docker installation
without user namespace remapping, allow container UIDs 70 (PostgreSQL) and
10001 (API) to read the applicable files with POSIX ACLs.

```sh
cd /srv/lifeapp
umask 077
mkdir -p secrets
openssl rand -hex 32 > secrets/db-password
openssl rand -hex 32 > secrets/db-admin-password
setfacl -m u:70:r,u:10001:r secrets/db-password
setfacl -m u:70:r secrets/db-admin-password
```

Create credentials only for a new install. PostgreSQL initializes them only
when the named data volume is empty. For rootless or remapped Docker,
adjust the ACLs to the corresponding host UIDs.

Configure the GitHub environment `lifeapp-image-publish` with required
reviewers and deployment branches restricted to `main`.
Run **Deployment images** from `main` with **publish** selected. After its
regression and disposable Compose checks, it publishes the tested images and
records their digest references and commit. Grant the repository access to
its private GHCR packages as needed.

On the VM, apply the release manually after authenticating to GHCR with
package-read access. To automate rollout, install
`rollout-private.yml.example` under `.github/workflows/` in a separate
private control repository with trusted collaborators only. Register the
`lifeapp-deploy` runner there. Restrict its `lifeapp-production` environment
to `main` and require approval where supported. Set `LIFEAPP_DEPLOY_DIR`
to `/srv/lifeapp`, provide `LIFEAPP_GHCR_USER` and a read-only GHCR credential
in `LIFEAPP_GHCR_READ_TOKEN`. The runner executes the reviewed bundle
already installed on the VM.

```sh
./scripts/apply-release.sh FULL_COMMIT API_DIGEST_REFERENCE WEB_DIGEST_REFERENCE
docker compose --env-file .env --env-file release.env exec api \
  /opt/lifeapp/bin/server init-owner
curl --fail http://127.0.0.1:4187/ready
```

Initialize the owner once on a terminal. The CLI prompts for a password and
keeps it out of the command line. Flyway runs on API startup. The stack uses
the `lifeapp` Compose project and persists PostgreSQL in its named volume.
The gateway is the only host binding. Keep the Docker socket and administrative
tools private.

## Tailscale HTTPS

Inspect existing routes before reserving an HTTPS port.

```sh
tailscale serve status
tailscale funnel status
ss -ltn
```

For tailnet access, use Serve on a free HTTPS port. The following example
uses 8443 and forwards to the gateway on port 4187. Set
`LIFEAPP_HTTPS_ORIGIN=https://YOUR-NODE.YOUR-TAILNET.ts.net:8443`.
Do not replace another service's route. [Tailscale Serve reference](https://tailscale.com/docs/reference/tailscale-cli/serve)

```sh
sudo tailscale serve --bg --https=8443 http://127.0.0.1:4187
```

For public access, first verify private HTTPS readiness, login/logout,
unauthenticated denial, browser install and offline reload, and Android sign-in.
After explicit public-exposure approval, switch only the dedicated port
from Serve to Funnel so the canonical origin stays the same. Check the tailnet
policy and repeat the unauthenticated and authenticated checks from outside
the tailnet. [Tailscale Funnel reference](https://tailscale.com/docs/reference/tailscale-cli/funnel)

```sh
sudo tailscale serve --https=8443 off
sudo tailscale funnel --bg --https=8443 http://127.0.0.1:4187
```

Verify that HTTPS certificate validation succeeds and that unauthenticated
`/v1/todos` returns 401. Sign in and out from the PWA and check that an offline
To-do survives a reload. Test Android connection and task sync against the same
origin using a disposable dataset and an independent Android backup.

## Roll back images

Run `./scripts/apply-release.sh --rollback` from the deployment directory
after confirming the previous release accepts the current database schema.
The script uses the previous verified digest references and retains the
volume. For an incompatible schema or database loss, follow the separate
[recovery procedure](../docs/server-backup-design.md) and restore into an
isolated project. Keep `release.env` and `previous.env` in the operator
recovery inventory alongside the recorded immutable digests.

## Run the isolated tests

With JDK 21, Android SDK, Node, Docker, Python 3.12+, OpenSSL, and age available:

```sh
export LIFEAPP_API_IMAGE=lifeapp-api:verification
export LIFEAPP_WEB_IMAGE=lifeapp-web:verification
deploy/scripts/build-images.sh
LIFEAPP_SMOKE_RESTORE=1 deploy/scripts/smoke.sh
```

The test creates disposable projects, passwords and a one-time age identity.
Its cleanup removes only those projects and their volumes.

## Phase 7G automatic server backup

Review the installed schedule and preserve an independent current recovery
copy before changing it. Configure LIFEAPP_BACKUP_MANAGED_ID with a new UUID
for this deployment and retain it with the configuration. Use a private,
existing LIFEAPP_BACKUP_DIRECTORY and a dedicated Drive destination.
The script creates a child directory and remote folder named
automatic-server-UUID. Keep the age identity off the VM.

Adjust the example systemd paths to the deployed directory. Test
`automatic-server-backup.py capture` with the operator environment. Verify
an independent encrypted download and restore before enabling
lifeapp-backup.timer and lifeapp-backup-retry.timer. Capture runs at
09:00 and 21:00 Asia/Kolkata. The retry timer checks the persisted queue.
Enable LIFEAPP_BACKUP_PRUNE=1 only after operational validation. Legacy
manual archives are outside this scope.
