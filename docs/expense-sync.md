# Expense synchronization

Phase 8A stores expenses locally on each device. No expense request or
operation is sent to the server. The existing editable-record endpoints
reject expense data in journal, planner, habit and notebook archives.

Phase 8B will add owner-scoped expense operations, first-binding review,
durable receipts and explicit conflict handling. Existing To-do and
editable-record sync continue through their current paths.

## Phase 8B reviewed connection

Connect Expenses explicitly after signing in. Android verifies a separate
local recovery ZIP before binding. Browser review compares local and server
record counts and retains independent IDs. Expense sync uses the existing
authenticated origin, dataset and recovery epoch. A scoped binding rejects
a changed recovery epoch without discarding local pending edits.

Each mutation records a stable operation and its causal parents in the
same Room or IndexedDB transaction as the edit. The server keeps immutable
dataset-scoped operations and competing heads. A deletion wins the ordinary
view while conflicting edits remain selectable in review. Explicit restore
and resolution check the current heads. A matching receipt plus replay
clears the outbox. Old backup replacement may proceed for a bound device
only when its expense ledger matches, preserving its sync state.

Equivalent category names with distinct IDs can be renamed or archived in
the manager. Automatic merging of historical references is deferred.
