# Reconciliation and operations

`POST /api/v1/reconciliation-runs` requires OWNER/ADMIN and returns 202 with the run ID and Location. One pending/running run per merchant is enforced by a partial unique index. GET list/detail and `/reconciliation-runs/{id}/discrepancies` require tenant membership. Findings use UUID keyset pagination; reports use creation-time/ID keyset pagination.

The worker runs independently of Kafka. It claims one durable run with `SKIP LOCKED` and a two-minute fenced lease. A separate proxied service executes the scan in a **REPEATABLE READ** transaction, limited to 30 seconds (individual SQL statements to 25 seconds). It locks only its run row, not wallet/account rows. All financial reads observe one snapshot. Findings, totals, completion and the audit event commit together. Readers cannot see a partial report.

Checks in Flyway's `reconciliation_findings(uuid)` SQL contract:

- Every journal has 2–100 entries across at least two accounts and net debits minus credits zero.
- Every account projection equals its ledger-derived normal balance.
- Successful payments/refunds and every transfer/funding reference the exact expected two-entry posting, accounts, amount, currency and business reference.
- Refund sums equal the payment's materialized refunded amount and do not exceed captured value.
- Known financial journal types have a corresponding resource; business references are unique.
- Entries have matching accounts/journals, tenant and currency.

`recordsProcessed` counts the examined account, journal, entry, payment, refund, transfer and funding rows, not SQL operations. `durationMs` measures scan processing. `snapshotAt` is the PostgreSQL scan transaction start. A clean report is PASSED; findings produce DISCREPANCIES. Neither result repairs data. Findings and completed reports are immutable to ordinary runtime workflows.

Crash before completion rolls back the findings and leaves the run reclaimable after lease expiry. Three abandoned claims exhaust recovery and mark FAILED. SQL failures roll back the scan and mark FAILED separately when PostgreSQL is reachable. If PostgreSQL remains unavailable, the lease remains the recovery mechanism. A stale worker cannot finalize a newer claim. Operators can request another run after a terminal result; financial history should be corrected only through reviewed compensating postings, never a reconciliation auto-repair.

Tests inject corruption using a privileged test-only connection that disables triggers for that connection. They prove detection of imbalance, projection and posting mismatch without repair; clean repeated runs, concurrent funding snapshots, tenant isolation and crash lease recovery are also covered. Runtime cannot perform that fixture corruption.

## Read models

`GET /api/v1/overview` returns lifetime INR payment/capture/refund totals, aggregate wallet liabilities, and 14 UTC days of payment counts. Monetary aggregates are decimal strings of integer minor units. `GET /api/v1/operations/health`, `/operations/outbox`, `/operations/dead-letters`, and `/audit-events` expose tenant-scoped operational metadata without raw event payloads. Unknown-tenant poison messages are not visible to merchant users; platform operators inspect Kafka/database using restricted tooling.

The `operations` module composes read-only SQL models. It does not become an owner of financial state. The dashboard is implemented in Phase 10. DLT replay tooling is not provided. Prometheus alert rules exist, but external alert routing is not configured.

## Limits

This is a bounded per-merchant full scan, not a distributed batch engine. Large histories that exceed the timeout need a reviewed incremental/checkpointed design with a stable accounting cutoff; increasing timeouts without considering MVCC vacuum pressure is not the scaling strategy. Reports are retained indefinitely pending an archival policy. Reconciliation cannot prove that a privileged administrator who rewrites all mutually consistent records did not tamper with history; signed external archives and database access controls address that separate threat.
