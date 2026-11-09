# Record compatibility and conflict rules

Use existing UUIDs and parent IDs. Archive is reversible metadata. A hard
delete uses a tombstone. Keep conflicting variants until an explicit resolution
names the complete reviewed head set. Same-base concurrent edits are never
ordered by wall-clock time.

| Record | Identity, ownership and reconciliation |
| --- | --- |
| To-do | Implemented in the fake-server path. Independent records converge. Same-record concurrent heads remain available for resolution. A deletion hides the projection while retaining the other variant. A deleted identity cannot be revived by a stale edit. |
| Journal entry | UUID plus unique local date. Concurrent creation for the same date needs an explicit canonical ID and child reparent mapping, preserving both source identities for retry. Hold unresolved until mapping is validated. |
| Journal page | UUID owned by entry. Keep archive state and minimum one active page. Divergent changes to archive state or the same page metadata require review. |
| Monthly planner page | UUID plus unique month. Preserve the versioned calendar geometry and cell ownership. Same-month creation and incompatible geometry need explicit reconciliation. |
| Journal, planner and notebook strokes | Immutable UUID, surface owner, source LJPT bytes and logical coordinates. Model add and remove separately. Deduplicate equal adds. Different bytes for the same ID are a conflict. A remove tombstone prevents stale add replay. An intentional restore needs a defined causal event. |
| Habit ledger and definition | Ledger UUID and month, definition UUID and ledger ownership. Reconcile same-month ledger creation explicitly. Archive a definition without losing historical completions. Concurrent conflicting definition edits require review. |
| Habit completion | Independently address a completion by UUID and unique habit/date. Merge disjoint checkboxes. Keep competing values for the same checkbox until resolved. |
| Notebook and notebook page | Notebook UUID and page UUID with immutable parent binding. Preserve archive flags and reject cross-owner reparenting. Divergent metadata edits require review. |
| Workout | Proposed exercise/session/entry/set UUIDs. Archive exercises in place. Retain competing session or set variants until an explicit merge policy exists. |

For ordered journal and notebook pages and workout entries, treat reorder as a
causal collection operation over stable IDs. Accept one successor to a known
order and retain concurrent competing orders for review. Do not reuse positional
integers as record identities. When concurrent additions claim the same position,
retain both and resolve their relative order before projecting to a unique index.
Validate the complete parent/child projection before advancing the cursor.

Phase 5 leaves non-To-do synchronization disconnected. The existing local Room
repositories, portable backup mappings and LJPT vectors remain their source of
truth. Real server reconciliation and conflict UI belong to Phase 6.
