# Phase 6 implementation plan

Phase 6A has its own review branch. It implements the Ktor To-do routes, PostgreSQL
migrations, authentication and isolated integration tests. See [server development](server-installation.md).
Milestones 6B–6E remain open.

1. Implement the versioned Kotlin Ktor HTTP API with health, readiness and contract tests.
2. Create PostgreSQL migrations for accounts, devices, datasets, records, causal operations, conflicts and transactional counters.
3. Implement owner authentication, browser cookie and CSRF policy, Android native token handling, revocation and login limits.
4. Implement transactional To-do push and pull, idempotency, bootstrap, conflict resolution and recovery epochs. Test concurrency against PostgreSQL before adding other record types.
5. Build the React/TypeScript PWA with IndexedDB projections and outbox. Start with To-do, then add selected journal, planner and notebook viewing.
6. Integrate Android HTTPS configuration, verified dataset binding and foreground/network-aware synchronization. Keep local editing and backups available during failures.
7. Add workout persistence and editing to Android and PWA, with explicit units and workout mutations covered by the contract.
8. Package Ktor and PostgreSQL in Docker Compose with health checks, isolated ports, migrations and reproducible configuration.
9. Add an authorized GitHub Actions deployment workflow with rollback and secret separation.
10. Validate private Tailscale Serve access and public Funnel access with required authentication in both modes.
11. Add consistent encrypted server backups, Drive upload, retention and a clean-VM restore drill.

Production acceptance requires authenticated cross-device editing, concurrent
conflict cases, interrupted requests, restore after VM loss, and offline editing
through server downtime. Phase 5 provides the contract and local tests only.
