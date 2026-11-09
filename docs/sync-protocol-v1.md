# Server synchronization protocol v1

The Phase 6A Ktor API implements To-do push, pull and conflict review against PostgreSQL.

The [OpenAPI specification](../contracts/openapi/lifeapp-v1.yaml) is the Phase 6
HTTP contract. Kotlin already implements the To-do operation and response codecs,
the coordinator, and the Room checkpoint transaction against a fake transport.
The Phase 6B browser client uses this API. Android server binding remains in 6C.

## Identity and commits

A server session authorizes a dataset UUID and a recovery epoch UUID. A database
binds explicitly to one such pair and rejects rebinding. Each operation contains
a stable operation UUID, record UUID, device UUID, positive per-device sequence,
record type, operation kind, causal parent UUIDs, protocol version and payload
version. Record revisions are metadata. Ordering comes from causality and server
commits. Sequences and revisions use decimal JSON strings bounded by signed
64-bit range so JavaScript clients can use BigInt or decimal strings without
precision loss. Calendar dates are ISO dates and times are UTC instants.

Push submits one immutable operation. The server validates versions, ownership,
record invariants, parent dependencies and device ordering. Operation IDs and
device/sequence pairs are unique in the dataset epoch. Identical retry returns
the original commit. Reusing an identity with different content returns
IDEMPOTENCY_MISMATCH. Reserve the commit number, store the operation, update
projection and retain conflict heads in one transaction. Use a transactional
per-dataset counter, since PostgreSQL sequences can leave gaps after rollback.
Commit numbers are strictly contiguous and parents precede their successors.

## Pull and failure recovery

Bootstrap returns sequence zero and the authenticated epoch. Pull starts strictly
after a durable cursor and returns up to 100 ascending contiguous commits.
A nonempty page advances to its last commit. An empty page has no continuation.
The client rejects mismatched scope, oversized pages, gaps, reordered commits
and stale checkpoint writes. Room applies a page and advances the checkpoint in
one transaction. An invalid operation rolls back the page. Equivalent duplicate
operation UUIDs replay idempotently. Receiving an echo does not clear its outbox
entry. Only an explicit acknowledgment carrying the matching scope and complete
submitted operation does that. A lost receipt is safe to retry.

The coordinator pulls before uploading so local edits can see known concurrent
heads. Each invocation drains at most one initial page, submits up to 100 pending
operations and pulls at most one final page. A continuation calls it again.
Durable pending state survives authentication failure and downtime. Future network
scheduling should respect Retry-After and back off with jitter on retryable
failures. Reject unsupported versions without clearing queued work. Cursor
expiry or a changed epoch requires a separate bootstrap and explicit reconciliation
of pending local operations. No automatic reset or compaction is implemented.

A confirmed local ZIP restore is a dataset replacement, separate from replay.
Unbound Room databases retain the established replacement workflow. Once bound,
Room rejects replacement in that database and requires a separate local database.
A future server recovery must allocate a new epoch and keep old clients' pending
operations available for review.

## Client fixtures

JSON examples in [contracts/fixtures](../contracts/fixtures/) validate against
OpenAPI 3.1 with the JSON Schema 2020-12 dialect. The Kotlin tests decode the same
To-do operation and pull page, including a value above JavaScript's safe integer
range. In Phase 6 extract the schemas or generate TypeScript types from OpenAPI.
Run runtime schema validation against the same examples and reject numeric
substitutes for counters. Never infer authorization from a schema match. All
error responses use a shared code, message and request ID. Retry-After is
documented for throttling.

- [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)
