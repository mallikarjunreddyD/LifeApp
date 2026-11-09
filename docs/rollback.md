# Android sync recovery

For an interrupted or unavailable server, keep using the local app. Retain
the original verified backup and make a fresh independent backup before
changing the installation. Retry after service returns.

To stop syncing, sign out in Settings. Your data and dataset binding remain
in Room. Sign back in to the same server, dataset and epoch to resume. A
server restored into a new recovery epoch requires a separate reconciliation
procedure. Preserve both sides before attempting any recovery.

Use a forward APK signed with the existing update key for app fixes. An older
APK may be unable to open the migrated database. For local backup recovery on
a bound installation, the backup must contain exactly the current To-do
records. Restoring another task history requires a separate offline database.
Keep the installed database and original archives until recovery is verified.
