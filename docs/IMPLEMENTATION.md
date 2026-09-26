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
| 5 | Payment lifecycle/API-key access/ledger capture; invalid transition and replay tests | Complete |
| 6 | Partial/full refunds; concurrent sum limit and insufficient-settlement tests | Complete |
| 7 | Outbox/Kafka/consumer markers/DLT; publish-crash, duplicate and unresponsive-broker recovery tests | Complete |
| 8 | Webhook encrypted secrets, approved/pinned destinations, signing, retries/replay; 500/timeout/duplicate tests | Complete |
| 9 | Snapshot reconciliation persisted reports; injected corruption and repeated-run tests | Complete (UI in Phase 10) |
| 10 | Dashboard with server-backed forms, errors/loading/pagination; RTL + Playwright critical flow | Complete |
| 11 | Metrics/logs/traces and actual provisioned observability verification | Complete; full exported webhook trace remains uninspected |
| 12 | Runtime images, Compose full workflow, K8s/Helm probes/resources/secret references | Complete; cluster rollout unverified |
| 13 | Redis limits, fault/load scripts, SQL plans; actual measured results only | Complete; load smoke only, no capacity claim |
| 14 | Full clean build/test/start/seed/E2E verification, docs audit, interview/resume | Complete with disclosed scope/deployment limits |

## Test design

Phase 6 evidence: backend build passed (7 unit/architecture tests, 23 PostgreSQL integration tests). Refund tests cover multiple partial/full compensation, original payment state/totals, simultaneous 800/700 requests against a 1000 payment (one accepted), concurrent same-key duplicate suppression, cross-tenant denial, settlement liquidity failure with no journal/total change, and stable failure replay after later funding. Deferred database checks enforce refund sum and exact original-account compensation. Frontend lint/typecheck/2 tests pass. Event records are still awaiting the Phase 7 publisher.

Phase 5 evidence: backend build passed (6 unit/architecture tests, 19 PostgreSQL integration tests). A merchant API key creates/confirms/replays a payment without a tenant header; successful capture posts once, changes exact balances and records its lifecycle. Tests cover two concurrent confirmations (one success, one conflict), failed/cancelled payments with no posting, key hashing, once-only secret exposure, read-only scope, key revocation, denied key access to dashboard administration, and cross-tenant reads. Frontend checks still pass. API keys use Spring Security's bearer authentication manager dispatch, not a handwritten servlet authentication filter. Refund workflows and event delivery remain pending.

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

## Phase 7 verification

Backend build passed with 7 unit/architecture tests and 28 PostgreSQL/Kafka integration tests. Five messaging tests cover dispatcher publication order, publish-before-mark crash/lease fencing, consumer transaction rollback, poison-message DLT persistence, and paused-broker recovery. Broker stop/start was not a successful test fixture on this host (advertised address became unreachable); the passing outage test uses Docker pause/unpause. Frontend lint, typecheck, two tests and production build passed. Compose events configuration and diff whitespace checks passed. No benchmark claims.

Remaining operations work: DLT replay UI, blocked-outbox administrative recovery, retention jobs, metrics and tracing. Webhooks, reconciliation and the full dashboard are subsequent phases.

## Phase 8 verification

Full backend build passed: 8 unit/architecture tests and 32 PostgreSQL/Kafka integration tests. Real HTTP receiver verifies exact-payload HMAC, HTTP 500 retry then 204 success, audited replay, timeout, denied destinations and cross-tenant access. Tests also verify encryption binding, expired-lease fencing, exhausted delivery state and real Kafka fanout. Frontend lint/typecheck/two tests passed. See WEBHOOKS.md for configuration, recovery semantics and limitations. Key rotation, webhook UI and managed egress infrastructure remain later/deployment work.

## Phase 9 verification

Backend build passed with 8 unit/architecture and 36 integration tests. New tests verify repeatable clean reports, concurrent-write snapshots, privileged corruption detection without repair, tenant isolation, active-run exclusion and abandoned-worker fencing. Overview, audit and operational read routes are verified with tenant scoping. Frontend lint/typecheck/two tests passed. See RECONCILIATION.md for full-scan timeout and retention limits; the operations UI belongs to Phase 10.

## Phase 10 verification

Backend build remains green (8 unit/architecture, 36 integration tests). Frontend lint, typecheck, four RTL/unit tests and production build passed. Chromium E2E against the running PostgreSQL-backed demo API passed registration/login, funding, transfer, balanced journal, payment capture, partial/full refund, API-key secret display/revocation, reconciliation and mobile overflow checks. Screenshots in docs/screenshots are from those actual executions. Read DASHBOARD.md for memory-only sessions, unresolved-request navigation and bounded collection limitations.

## Phase 11 verification

Backend build passed with 9 unit/architecture tests and 40 PostgreSQL/Kafka integration tests. New coverage verifies metrics authentication, HTTP trace persistence, context restoration, commit-only counters and real Kafka trace continuity into webhook jobs. Frontend lint/typecheck/four tests/build passed. Compose configuration, Prometheus rules, Grafana health and Tempo readiness passed. Live host scrape/export inspection remains pending after automatic tool review blocked the backend restart with local secrets. See OBSERVABILITY.md for exact instrumentation and deployment limits.

## Phase 12 verification

Non-root backend/frontend images built from source; separate Flyway container migrated through V10 before API startup. Compose health checks passed and Chromium's financial flow passed through packaged Nginx. Live Prometheus scrape and Tempo-exported HTTP/ledger/outbox spans were verified. Backend build passed (9 unit/architecture, 40 integration tests). Helm lint/template passed; no Kubernetes cluster context is available, so rollout remains unverified. Clean-volume packaged E2E is configured in CI; remote CI has not run here. See DEPLOYMENT.md for secret/bootstrap/managed-service prerequisites.

## Phase 13 verification

Backend build passed with 9 unit/architecture and 44 integration tests, including real Redis atomic concurrency, TTL expiry, verified-principal isolation, HTTP throttling and paused-Redis recovery. Packaged financial Chromium flow passed with Redis limiting enabled. k6 2.3.0 eight-second smoke passed all checks; no capacity claim. Read-only PostgreSQL EXPLAIN ANALYZE output is retained with small-fixture limitations. Existing Kafka/webhook retries remain bounded and idempotent; no blind financial retry or speculative index was added. See REDIS.md and PERFORMANCE.md.

## Phase 14 verification (2026-09-26)

Final backend build passed: 9 unit/architecture tests and 46 integration tests against PostgreSQL, Kafka and Redis. Added generated OpenAPI/Swagger verification (42 real paths) and a paused-PostgreSQL outage/recovery test. The latter found rollback exceptions being masked as 403; those now return safe retryable 503 responses. Cached test context pools were reduced to 10 connections to remain within the shared container database budget; the 100-request spending test still passes.

Frontend lint/typecheck/four tests/production build passed. Chromium passed the real packaged financial journey against a separate fresh Compose stack. Its new PostgreSQL volume applied all 10 Flyway migrations, then the demo seed created succeeded/failed/partial/full refund examples and passed reconciliation. All 39 events present after seed plus browser flow were published and both consumer groups stored 39 processed-event markers. No external webhook endpoint was seeded without explicit origin configuration; real HTTP retry/signature behavior is covered by integration tests.

Helm lint and render/example consistency passed; no Kubernetes context exists. Source text hygiene passed and npm audit reported zero vulnerabilities at execution time (including development dependencies); no complete backend advisory scanner claim. k6 smoke and small-fixture SQL plans are documented without production-scale extrapolation. README/API/security/database/ADR documents were reviewed against source; interview/resume statements use only implemented evidence. Remote GitHub CI execution, cluster rollout, managed-provider failover/restore and a full exported webhook trace remain unverified. Other known limitations are listed in README.md.
