# Android server synchronization

Phase 6C synchronizes To-do records with the browser PWA. Local editing works
without an account or connection. Daily Journal, handwriting, Monthly Planner,
Habit Tracker and Notebook stay on the device and in existing backups. Drive
backup and To-do reminders continue to run independently.

In Settings, enter the server's HTTPS origin, test connectivity and sign in.
Android uses normal certificate and hostname verification and encrypts the
native session token with Android Keystore. It saves the selected origin,
dataset and recovery epoch with the Room checkpoint. A different origin,
dataset or epoch stops synchronization before uploads.

Follow the [first-sync procedure](initial-data-migration.md) before enabling
sync. Settings shows pending changes, the last successful check and any task
conflicts. Use **Sync now** to fetch remote changes while the app is open.
WorkManager also queues work on local edits and foreground entry and waits
for a network. Pending edits survive restarts. Delivery uses stable operation
IDs, and Android retries temporary failures with backoff. Signing out clears
the local credential and attempts to revoke the remote session. The local
database remains associated with its original dataset.

Concurrent task edits are displayed as versions. Select a version to resolve
an edit conflict. Where one version deletes the task, keep the deletion and
use the displayed edited text to create a separate task if needed. A stale
resolution is retained in local history, and the latest conflict can be reviewed.

Use a dedicated disposable API 36 emulator to run the Android/PWA integration
test. Install the repository's JDK, SDK and Node versions, Docker, OpenSSL and
Playwright Chromium, then run from the repository root:

```sh
(cd apps/web && npm ci && npx playwright install chromium)
LIFEAPP_ANDROID_BRIDGE=1 apps/web/scripts/test-browser.sh
```

The harness runs a disposable PostgreSQL database and Ktor server, and creates
a temporary TLS certificate trusted only by the Android test transport.

See [Automatic sync](automatic-sync.md) for the Phase 7G foreground,
committed-edit and deferred sync triggers.
