# Phase 7G acceptance

- Settings navigation and hidden diagnostics are covered by Android and
  browser fixtures.
- Automatic sync builds on the To-do outbox and scoped record archive
  tests. Browser visibility and Android WorkManager are subject to
  the device's foreground, connectivity and battery policy.
- Server tests use disposable PostgreSQL and an isolated rclone
  destination. Production setup requires an independent backup,
  renewable Drive authorization, encrypted upload and read-back,
  recovery into an isolated database, and a scheduled-run check.
- Android production acceptance requires the signed APK on Xiaomi Pad 7,
  a disposable Google account, offline capture, reconnect upload,
  reauthorization, and restore on a clean installation using only the
  independent passphrase. Check both surviving verified generations.
- Verify iPhone Safari visibility-triggered sync and ZIP download on
  physical hardware. Browser automation uses Chromium.
