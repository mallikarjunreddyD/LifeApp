# Expense labels

Default labels have stable IDs. Search orders recently used active labels
first. Matching folds whitespace and uses locale-independent Unicode
lowercasing. The lookup does not collapse distinct Unicode normalization
forms or automatically combine different IDs. An exact normalized name
blocks duplicate creation, including when the existing label is archived.
Restore an archived label in the manager to use it again.

Manage Categories supports renaming, color and icon changes and archive or
restore. Manage Payment Methods supports naming and archive or restore.
Existing expense references remain attached to the same UUID. Category
merging awaits the synchronization reconciliation design.
