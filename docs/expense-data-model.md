# Expense data model

An expense uses a UUID, local calendar date, INR amount, category and payment
method UUIDs, optional remarks, revision metadata and a recoverable deletion
marker. The amount is a positive integer from 1 through 99,999,999,999 paise.
Kotlin uses Long, TypeScript uses BigInt for arithmetic, and portable data
encodes the amount as a decimal string. The entry fields accept zero to two
fractional digits and correctly grouped thousands. Display uses Indian
grouping and exactly two decimal digits.

Room version 10 adds local_expense_records. IndexedDB version 3 adds expenses
to the existing LifeApp database. Local edits check the original revision.
Deletion updates only the selected UUID and restore retains its identity.
Historical category and payment references stay valid after archival.

Portable Android data schema 10 includes the expense ledger in the existing
validated backup. Readers continue to accept schemas 4 through 9. Opening
Expenses after an older restore seeds an empty ledger. Restoring an older
backup uses the existing whole-device replacement and verified recovery-copy
confirmation, so the pre-restore backup preserves existing expenses.
