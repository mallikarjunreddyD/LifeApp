# LifeApp browser app

Phase 6B adds a React and TypeScript PWA under apps/web. It uses the same-origin
Ktor API and a transactional IndexedDB store. To-do is the default screen.
Workouts and History provide authenticated read-only views. Browser workout
editing and synced journal browsing depend on later milestones.

## Run the browser tests locally

Use Node 22.12 or later in the Node 22 release line, npm, Docker, OpenSSL, Python 3,
curl, the Linux script command, JDK 21 and the project's Android SDK. On Linux:

```sh
cd apps/web
npm ci
npx playwright install --with-deps chromium
npm run typecheck
npm run lint
npm test
cd ../..
apps/web/scripts/test-browser.sh
```

The script builds the web app and server, creates a disposable PostgreSQL
instance, initializes an owner and serves the PWA at https://127.0.0.1:4186
through a local Vite proxy. It uses a temporary certificate and test-only
credentials. The browser tests allow this certificate only in their test profile.
The server and database are removed when the script exits.

For web-only development, run npm run dev from apps/web. To exercise sign-in
with a development backend, provide LIFEAPP_WEB_API and the two local certificate
paths LIFEAPP_WEB_TLS_KEY and LIFEAPP_WEB_TLS_CERT. Use the same HTTPS origin in
the backend's LIFEAPP_HTTPS_ORIGIN. Real use requires a certificate trusted by
the device.

## Use a configured HTTPS instance

1. Open the LifeApp HTTPS origin and load the app once while online.
2. To work offline, create, edit, complete or delete tasks from To-do. The task
   and the outgoing operation save in the same IndexedDB transaction.
3. In Settings, sign in to the owner account. Verify the dataset and recovery
   epoch, then choose Use this dataset to confirm where queued records will go.
4. The app syncs when it opens online, reconnects, or saves a new local edit
   while connected. Use Sync now for a manual retry. Review concurrent versions
   in To-do. A tombstone takes precedence in the local projection.
5. If the server reports a different dataset or epoch, local data remains on the
   device. A different origin or browser profile uses separate storage. Sign-out
   revokes the server session and retains local task data in that browser profile.

Browser storage is per device and can be cleared by Safari or by the owner.
Pending changes exist only in that browser until the server acknowledges them.
Authenticated API responses are never placed in the service worker cache. The
worker caches built shell assets and offers Reload app when a new shell is
ready. Reloading replaces the shell without deleting IndexedDB.

## iPhone physical acceptance checklist

Phase 6B automates an iPhone 12-sized Chromium viewport. Physical iPhone Safari
installation remains to be checked after a stable HTTPS instance is deployed.

1. In Safari, open the LifeApp HTTPS URL, then choose Share → Add to Home Screen.
2. Open the icon and confirm it launches in standalone mode on the correct origin.
3. Confirm the safe areas and bottom navigation in portrait and landscape.
4. Create a task, close and reopen the PWA in airplane mode, and edit the task.
5. Reconnect, sign in and confirm that the edit appears in the other browser.
6. Install an app update with a pending task and verify that the task stays queued.

No Apple provisioning, Xcode or IPA is needed for a Home Screen web app.
The production HTTPS origin and reverse proxy are Phase 6E work.

- [Apple iPhone Home Screen web app instructions](https://support.apple.com/guide/iphone/bookmark-a-website-iph42ab2f3a7/ios)
