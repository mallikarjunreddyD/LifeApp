# Phase 8 acceptance

Use a disposable account and dataset. Keep an independent Android backup before
testing replacement. See [validation](validation.md) for automated results.

## Device checks to record

- Xiaomi Pad 7. Update in place using the existing signing identity. Confirm
  existing Journal, Planner, Habits, Notebook, To-do and Workout records still open.
  Add an expense offline, create both labels, force-stop and reopen. Check its date,
  amount and remarks. Exercise portrait, landscape and large text.
- Physical iPhone Safari and installed PWA. Repeat offline entry and reopening,
  including after a shell update. Verify keyboard, date/month controls and the
  narrow chart legend. Reopen the PWA after reconnecting to let it sync.
- Windows desktop browser. Connect Expenses and verify local and remote inventory.
  Confirm independent offline expenses from each device arrive once. Rename and
  archive a shared label and verify historical references remain valid.
- Edit the same expense offline on two devices. Delete it on one and reconnect.
  Verify deletion remains in Recently Deleted and the competing edit is selectable.
  Resolve and check both views. Create two identically named categories on separate
  devices and reconcile them by rename or archive.
- Check monthly, yearly and empty periods against manually calculated paise totals.
  Download My Data offline and verify JSON, CSV and manifest coverage. Test schema-11
  Android recovery on a disposable installation with independently recoverable credentials.

## Production rollout

1. Get separate user approval. Capture a verified encrypted backup using the existing
   procedure. Record its checksum, current image digests and signing certificate.
   Verify the backup can be recovered independently.
2. Use immutable API/PWA digests from verified main. Verify Flyway V10 and backward
   compatibility against a disposable restored database.
3. Follow the [LifeApp release procedure](../deploy/README.md) for the LifeApp Compose
   project on Ubuntu. Preserve volumes, Tailscale, MFA, backup credentials and backup
   history. Verify readiness, sign-in, scoped sync and backup status.
4. Obtain the persistent-key installable APK from main. Compare its signer with the
   repository pin before installing it over the existing app.
5. For a regression, follow the documented image rollback procedure. Keep additive
   tables and data. Recover an encrypted backup into an isolated project before any
   cutover. A new recovery epoch blocks old bindings while retaining device-local
   pending changes. Preserve device databases and backups for reconciliation.

Production rollout and physical device acceptance are pending.
