# Failure modes

Status: intended recovery contract; fault tests are mandatory before claiming implementation.

| Scenario | Outcome/data loss and money duplication | Event duplication | Recovery / intervention |
| --- | --- | --- | --- |
| PostgreSQL unavailable | Commands fail 503; no alternative financial store. Unknown commit outcome is resolved using same key after recovery. No deliberate money duplication. DB durability still depends on WAL/backups/replication configuration. | Existing events may replay after recovery. | Restore DB; verify ambiguous requests by key. Operator needed for prolonged outage/data loss. |
| Redis unavailable | Credential/mutation requests fail closed 503; authenticated reads may continue. Committed finances are unchanged; no money lost/duplicated by cache loss. | None caused directly. | Redis recovery restores limits; alert on degraded mode. |
| Kafka unavailable | Payment commits with pending outbox; no broker call in payment transaction. Money unchanged. Backlog grows and disk must be monitored. | Retries can duplicate after ambiguous sends. | Publisher retries with cap/jitter; operator if sustained backlog/disk pressure. |
| API dies halfway through transfer | Uncommitted business, projection, journal, key and event roll back together. No partial spend. | No committed event from rolled-back command. | Client retries same key; DB releases connection locks. |
| API dies just after commit | Command exists even if client saw timeout; no money duplication on same-key retry. | Outbox remains eligible. | Replay stored response; no manual repair ordinarily. |
| Publisher dies after publish | Financial state already committed. | Yes, stable event ID sent again. | Lease expiry and consumer deduplication; alert if backlog persists. |
| Kafka delivers twice | No consumer financial posting in this design. | Yes, expected. | ProcessedEvent + effects transaction suppresses duplicate local effects. |
| Consumer dies midway | Pre-commit effects/marker roll back; post-commit effects remain. No money movement. | Kafka redelivers if offset uncommitted. | Same event retries; unique marker protects effects. |
| Poison event | Financial record remains; consumer may move event to DLT after acknowledged DLT publish. | DLT retry/replay can duplicate. | Alert; fix schema/handler, audited replay original ID. Human action needed. |
| Webhook down six hours | Payment unaffected; no financial data loss. Attempts retained and backoff continues until budget exhausted. | Delivery is at-least-once, including timeout after receiver accepted. | Retry schedule targets >=24h window; exhausted deliveries await manual replay. |
| Webhook worker dies after accepted HTTP | Queue state may still be IN_FLIGHT. Money unaffected. | Yes, endpoint receives duplicate on reclaim. | Lease expiry; receiver dedupes stable event ID. |
| Duplicate transfer requests | Unique key serializes identical requests; business reference protects duplicate postings. Different keys intentionally mean distinct transfers. | One logical outbox event, possibly multiple broker deliveries. | Replay or 409 fingerprint mismatch. |
| Simultaneous spend | Account locks serialize balance checks; insufficient request rejects. No negative liability. | Accepted commands emit independent events. | Normal response; bounded timeout may require same-key retry. |
| Simultaneous refunds | Payment row lock serializes cumulative limit; account locks protect funding. No over-refund/overdraft. | Accepted refunds may be redelivered. | Normal response or 409/failed outcome; no manual repair. |
| Lease expires during send | Fence prevents stale worker overwriting state, not external side effects. No financial change. | Duplicate Kafka/HTTP delivery possible. | Renew/bound timeouts; idempotent consumer/receiver. |
| Reconciliation finds imbalance | Detection never edits history; suspend affected tenant's writes pending investigation. Corruption means a guard/bypass/privileged-write issue, not routine business failure. | No automatic corrective events. | Operator investigates snapshot and audit, repairs via approved projection rebuild or compensating journal, reruns. |
| Disk full / DB commit failure | Transaction fails or outcome ambiguous; do not return success before commit. | Pending events retained if commit succeeded. | Capacity recovery, same-key lookup, alert; operator likely. |

Retries are safe only with stable command/event identities or a read-only operation. A timeout is not proof that a receiver did nothing. Database restore to an earlier point may lose acknowledged commits or deduplication markers; recovery must reconcile backups, event history and external receipts before reopening writes. No claim of zero data loss or exactly-once external effects.
