# LifeApp server development

Phase 6A implements the Ktor To-do API and Flyway migrations on PostgreSQL.
It does not deploy to the shared Ubuntu VM. Android remains offline and unchanged.

## Reproduce the isolated test

With Docker, Python 3, curl, JDK 21 and the project's Android SDK configured:

```sh
apps/server/scripts/test-postgres.sh
python3 -m venv /tmp/lifeapp-contract
/tmp/lifeapp-contract/bin/pip install -r contracts/requirements.txt
/tmp/lifeapp-contract/bin/python contracts/check.py --responses apps/server/build/contract-responses
```

The script creates a disposable PostgreSQL container with a temporary data
directory, binds its port only to loopback, runs the integration suite and builds
the server distribution. It then starts the packaged Netty server on loopback
and checks liveness, readiness and a rejected unauthenticated request. The
container and the temporary password file are removed after the test.

## Configure a development instance

Use a dedicated database and role. Provide the database password in an owner-only
file. The JDBC URL must contain only connection details.

```sh
./gradlew :apps:server:installDist
export LIFEAPP_DB_URL='jdbc:postgresql://127.0.0.1:5432/lifeapp'
export LIFEAPP_DB_USER='lifeapp'
export LIFEAPP_DB_PASSWORD_FILE='/absolute/private/path/lifeapp-db-password'
export LIFEAPP_HTTPS_ORIGIN='https://lifeapp.example.test'
apps/server/build/install/server/bin/server init-owner
apps/server/build/install/server/bin/server
```

Owner initialization requires a terminal and prompts for the password without
echoing it. It creates the owner, dataset and initial recovery epoch in one
transaction. A second initialization fails without replacing the first owner.
Flyway runs before starting the HTTP listener and disables its clean operation.
The service binds to 127.0.0.1:8080 by default. The canonical HTTPS origin is
required for browser requests. The loopback development listener is plain HTTP
and intended to sit behind a separately configured HTTPS proxy. No Tailscale
route or production Compose project is configured in this milestone.
