# Implementation roadmap and evidence

## Repository inspection (2026-09-25)

Initial commit `51a0bc7`, LICENSE only, clean worktree. No existing application, AGENTS.md, tests or infrastructure. Available: Oracle Java 25.0.1, Node 24.11.1/npm 11.3.0, Docker Desktop Linux engine 29.8.0. Docker and network access require approved tool execution. Gradle absent; use checked-in wrapper with distribution SHA-256.

## Phase gates

At each code phase: compile backend, run unit/architecture and relevant real-infrastructure tests, lint/typecheck/test frontend where present, validate changed Compose, review diff/dead code, update evidence/limitations, commit a logical change when permitted. Never label skipped/blocked tests passed. Phase 0 has no source/build to compile and no Compose to validate; gates are document consistency and source verification.

| Phase | Deliverable / acceptance evidence | Status |
| --- | --- | --- |
| 0 | Version matrix, modules, schema, financial invariants, failure/API contracts, ADRs, review | Complete (design only) |
| 1 | Gradle wrapper/Boot health + Flyway baseline/PostgreSQL container, React shell, CI; clean builds and health integration test | Complete |
| 2 | Identity/merchant/RBAC and signing-key foundations; login/refresh/revoke/tenant-isolation tests | Complete |
| 3 | Restricted DB posting function, balanced immutable ledger/projection; malformed/append/mutate/rollback tests | Complete |
| 4 | Wallet/funding/transfer/idempotency; 100-request spending and duplicate races | Complete |
| 5 | Payment lifecycle/API-key access/ledger capture; invalid transition and replay tests | Pending |
| 6 | Partial/full refunds; concurrent sum limit and insufficient-settlement tests | Pending |
| 7 | Outbox/Kafka/consumer markers/DLT; publish-crash, duplicate and broker-down tests | Pending |
| 8 | Webhook encrypted secrets, SSRF-safe delivery, signing, retries/replay; 500/timeout/duplicate tests | Pending |
| 9 | Snapshot reconciliation persisted reports; injected corruption and repeated-run tests | Pending |
| 10 | Complete dashboard with server-backed forms, errors/loading/pagination; RTL + Playwright critical flow | Pending |
| 11 | Metrics/logs/traces and actual provisioned observability verification | Pending |
| 12 | Runtime images, Compose full workflow, K8s/Helm probes/resources/secret references | Pending |
| 13 | Redis limits, fault/load scripts, SQL plans; actual measured results only | Pending |
| 14 | Full clean build/test/start/seed/E2E verification, docs audit, interview/resume | Pending |

## Test design

Phase 4 evidence: backend build passed (5 unit/architecture tests, 16 PostgreSQL integration tests). With 1,000,000 minor units and 100 concurrent transfers of 100,000, exactly 10 were accepted and 90 rejected; source ended at zero, destination at 1,000,000 and all journals balanced. Twenty concurrent identical requests returned one stored response and created one transfer. Tests verify fingerprint mismatch, expired-key tombstones, fractional amount rejection and cross-tenant wallet isolation. Audit, ledger, projection, outbox intent and idempotency response commit together. Frontend lint/typecheck/2 tests pass; Compose config passes. Kafka publication is not implemented yet; committed event intent is durable in outbox_event.

Phase 3 evidence: backend build passed with 4 unit/architecture tests and 12 PostgreSQL integration tests. Ledger tests prove balanced postings/projection, insufficient-funds no-op, transaction rollback, forbidden direct writes, malformed/cross-tenant rejection, empty-journal deferred rejection, old-journal append rejection, exact reversal and duplicate business-reference rejection. Frontend lint/typecheck/2 tests pass. Compose bootstrap started PostgreSQL with separate login and non-login roles; the packaged application applied migrations and readiness returned UP. SQL privilege inspection confirmed runtime direct entry INSERT=false, account UPDATE=false, posting EXECUTE=true. Host port 5432 was unavailable, so ignored local .env uses 55432. Financial command APIs are Phase 4 onward, not implied by the ledger engine.

Phase 2 evidence: backend build passed, including 2 unit/architecture tests and 7 real PostgreSQL integration tests. Tests cover Argon2 storage, tampered JWT rejection, tenant isolation, VIEWER denial, membership revocation, refresh reuse family revocation, logout, immutable audit and two concurrent owner removals (exactly one succeeds). Frontend lint/typecheck, 2 tests and production build passed; Compose config remains valid. Public module boundaries and cycles are checked. Credential rate limits, browser login UI, multi-key rotation, MFA/email verification/password recovery are explicitly not yet implemented. Refresh transport was simplified to explicit body tokens, with memory-only browser storage planned; SECURITY.md and ADR-009 reflect the decision.

Phase 1 evidence (2026-09-25, Windows/Docker Desktop): Gradle 9.8.0 `build --write-locks` passed using Java 25.0.1; 1 architecture test and 2 PostgreSQL 18.6 integration tests passed. Flyway 12.4.0 migrated a fresh database; Boot 4.1.1/Hibernate 7.4.5 started and readiness was UP; private routes returned 401. Frontend lint/typecheck, 2 Vitest/RTL tests and Vite production build passed with verified Node 24.21.0. Compose config validated with events profile. CI authored, not executed remotely. No Kafka/Redis functional integration claimed yet.

Resolved compatibility issues: TypeScript 7 was outside ESLint peer support (pin 6.0.3); jsdom requires newer Node 24 (pin 24.21.0); host JAVA_HOME referred to removed JDK 24; malformed host PATH quotes interfered with Testcontainers fallback detection; legacy host timezone Asia/Calcutta was rejected by PostgreSQL image (run JVM/tests in UTC). All fixes avoid lowering compilation strictness or skipping integration tests.

Phase 0 checks: local Markdown links resolve; `git diff --check` passes. Official registry metadata confirms stable Boot 4.1.1 (4.2.0-M2 excluded), Gradle 9.8.0, Modulith 2.1.1, springdoc 3.1.1, ArchUnit 1.5.0 and the README npm versions. Adoptium reports Java 25.0.4+101; Redis release API reports 8.10.2. Gradle distribution downloaded and SHA-256 verified. No application/test compilation applies to the documentation-only phase. Framework compatibility is documented, runtime compatibility remains a Phase 1 gate.

Use JUnit/AssertJ for pure domain rules and Mockito only at external boundaries. Testcontainers PostgreSQL is mandatory for migrations/locks/triggers/isolation; Kafka and Redis containers for relevant integration suites. No H2. Architecture tests reject cross-module internal access/cycles. SQL tests use runtime role, including direct malformed postings. REST tests verify Problem Details and tenant scope. E2E tests use real backend and seeded isolated tenants, not intercepted fake financial responses.

High-value failure tests: crash window after outbox send, consume commit before offset, duplicate Kafka event, malicious webhook URL, HTTP 500/timeout, Redis unavailable, Kafka unavailable, ambiguous API timeout replay, fingerprint mismatch, simultaneous refund limits. Test fixtures may mock external HTTP endpoints; production repositories remain PostgreSQL backed.

## Review findings addressed in Phase 0

- Amount ambiguity resolved: all integer API amounts are minor units.
- Wallet liability semantics and funding asset account defined.
- Entries appended to old balanced journals prohibited, not merely UPDATE/DELETE.
- Projection owned by ledger to avoid duplicate mutable wallet balances.
- Aggregate outbox ordering explicitly enforced; Kafka key alone insufficient.
- Payment caller/tenant/customer authority specified as simulation-only.
- Refund liquidity failure and last-owner removal races addressed.
- Expired idempotency tombstone prevents silent key reuse.
- Reconciliation consistent snapshot avoids false positives during writes.
- Framework event registry, Batch, distributed locks and premature microservices deferred.
