# First Android server sync

1. Update in place using the persistent-key APK. Open the app and check that
   your journals and notebooks are present.
2. Open Settings, sign in to the intended HTTPS server and check the account,
   dataset and recovery epoch. Preview the number of local tasks and whether
   the server already has task history.
3. Choose a new backup ZIP in a location outside the app, such as Downloads.
   Keep a separate copy. LifeApp writes the full archive and reads it back to
   verify it before saving the binding.
4. Allow the initial sync to finish and check the To-do list in Android and the
   PWA. Android compares the visible task IDs on both sides before marking the
   initial sync verified.

Start with existing tasks on one side only. If both sides contain history,
including deleted records, the initial import is blocked. Keep both copies
and reconcile separately before attempting a new binding. Only To-do records
are exchanged. The archive also contains the journal, handwriting, notebook,
planner and habit data for local recovery.

If the app stops during first sync, reopen it and use **Sync now**. The saved
binding and outbox let it retry the same operations. If the server recovery
epoch changes, stop and follow the [recovery procedure](rollback.md).
