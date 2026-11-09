# Self-hosted LifeApp architecture

Phase 5 prepares the Android client and shared code. The Ktor service, PostgreSQL,
React/TypeScript PWA and deployment belong to Phase 6.

| Component | Responsibility |
| --- | --- |
| Android | Native UI and editable Jetpack Ink, Room and the local outbox, local reminders, export, local restore and user-controlled encrypted Drive backup |
| Shared Kotlin | Portable records, workout validation, causal To-do replay, checkpoint and transport contracts |
| Future Ktor service | Authenticate sessions, authorize records, reconcile operations, assign commits, expose versioned REST and report recovery status |
| Future PostgreSQL | Authoritative projections, immutable operation history, conflict heads, dataset generations and transactional commit counters |
| Future PWA | iPhone and desktop browser UI, IndexedDB for offline projections and queue, To-do and workout editing, selected journal and ink viewing |

The reusable parts are Room repository transactions, immutable LJPT vectors,
stable IDs, editor save queues, snapshot/ZIP validation, and the To-do causal
graph. The Phase 4 transport exposed a full-list read and treated a successful put
as acknowledgment. It had no server cursor or dataset identity. The replacement
separates explicit receipts from feed replay and stores each replay checkpoint
alongside its applied records. Other repositories still make local-only writes.

Android starts in `ServerConnection.Unconfigured`. The configuration boundary
accepts a validated HTTPS origin without user information, path, query or
fragment. Phase 6 will let the owner enter their Tailscale HTTPS origin, test
identity and certificate trust, authenticate, and confirm the dataset before
binding. No Android settings UI or production network adapter uses this boundary
in Phase 5. Existing Drive backup uses its separate account and adapter.

## Access and threat model

Tailnet-only Serve limits who can reach the service. Funnel exposes the HTTPS
endpoint publicly. In either mode, the service requires an application session
for every private resource. A URL, tailnet identity header, supplied account ID
or client device ID is never sufficient authorization. Keep PostgreSQL and
administrative ports off the public network. Bind Ktor to the proxy/container
network and restrict forwarded headers to the trusted proxy.

Start with one owner and server-managed, revocable sessions. Use an established
identity provider or reviewed password hashing with unique salt and calibrated
Argon2id. Rate limit login by account and source, return uniform failures and
redact credentials from logs. Browser sessions use same-origin requests and a
Secure, HttpOnly, SameSite host-only cookie. Validate Origin and a session-bound
CSRF token on mutations. Android uses a native session flow with short-lived
bearer tokens and platform-protected storage. Rotate session identifiers at
login, enforce expiry, and revoke at logout or credential reset. Do not put
credentials or deployment URLs in the repository.

Derive dataset authority from the authenticated principal. Check every lookup,
push, pull, conflict and backup query against that dataset. Verify causal parent
ownership and identifiers before writes. Reject oversized and unsupported
payloads. Avoid private record content in logs, errors, metrics, URLs and backup
names. Treat server compromise, theft of session credentials, malicious inputs,
cross-account replay, and VM/Drive loss as distinct threats. Rate limiting,
authorization, TLS, durable queues and independent recovery protect different
failure paths.

- [Tailscale Serve](https://tailscale.com/kb/1312/serve)
- [Tailscale Funnel](https://tailscale.com/kb/1223/funnel)
