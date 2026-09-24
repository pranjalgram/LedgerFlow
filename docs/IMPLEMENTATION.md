# Implementation roadmap and evidence

## Repository inspection (2026-09-25)

Initial commit `51a0bc7`, LICENSE only, clean worktree. No existing application, AGENTS.md, tests or infrastructure. Available: Oracle Java 25.0.1, Node 24.11.1/npm 11.3.0, Docker Desktop Linux engine 29.8.0. Docker and network access require approved tool execution. Gradle absent; use checked-in wrapper with distribution SHA-256.

## Phase gates

At each code phase: compile backend, run unit/architecture and relevant real-infrastructure tests, lint/typecheck/test frontend where present, validate changed Compose, review diff/dead code, update evidence/limitations, commit a logical change when permitted. Never label skipped/blocked tests passed. Phase 0 has no source/build to compile and no Compose to validate; gates are document consistency and source verification.

| Phase | Deliverable / acceptance evidence | Status |
| --- | --- | --- |
| 0 | Version matrix, modules, schema, financial invariants, failure/API contracts, ADRs, review | Complete (design only) |
| 1 | Gradle wrapper/Boot health + Flyway baseline/PostgreSQL container, React shell, CI; clean builds and health integration test | Pending |
| 2 | Identity/merchant/RBAC and keys foundations; login/refresh/revoke/tenant-isolation tests | Pending |
| 3 | Restricted DB posting function, balanced immutable ledger/projection; malformed/append/mutate/rollback tests | Pending |
| 4 | Wallet/funding/transfer/idempotency; 100-request spending and duplicate races | Pending |
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
