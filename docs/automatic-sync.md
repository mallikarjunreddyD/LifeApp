# Automatic sync

To-do sync starts after sign-in and the existing first-connection review.
Android uses connected WorkManager jobs on startup, resume and committed
To-do edits, plus a five-minute trigger while the activity is active. Tracked
record uploads also follow committed editor writes. WorkManager holds
deferred uploads until connectivity returns. Applying downloaded editable
records remains on the foreground entry path so editor caches can be rebuilt
with the existing save and replacement safeguards.

A successful reviewed record transfer can enable tracked and new record
sync by default for that server and recovery epoch. Existing untracked
records and conflicting versions remain eligible for explicit review.
An explicit pause stays in effect across sign-in for that connection.

The PWA checks on startup, sign-in, reconnect, visibility and local edits.
It checks about every five minutes while visible and signed in. Temporary
failures use bounded exponential delay with jitter and honor the server's
Retry-After deadline. IndexedDB keeps outgoing operations until matching
receipts arrive. Safari can suspend the app when it leaves the foreground.

Neither client automatically adopts a changed recovery epoch or clears an
outbox on session expiry. Workout writes remain local.

Connected expenses use local-write, active five-minute and reconnect triggers.
Android uses WorkManager for eligible retries. Browser sync depends on the PWA
being active and resumes on sign-in or visibility restoration. See
[expense synchronization](expense-sync.md).
