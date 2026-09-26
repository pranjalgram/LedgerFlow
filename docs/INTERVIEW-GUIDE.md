# Interview guide

## 30-second explanation

LedgerFlow is a simulated merchant payment and wallet platform built with Java 25, Spring Boot and PostgreSQL. Its source of truth is an immutable double-entry ledger. I focused on concurrent spending, transaction boundaries, durable idempotency and asynchronous recovery: financial changes and outbox events commit together, Kafka consumers deduplicate persistently, and signed webhooks retry independently. A React dashboard exposes the actual APIs, and real-container tests verify invariants and failure cases.

## Two-minute explanation

The problem is not merely storing payments: clients retry, requests race, processes crash between database and broker actions, and merchant endpoints time out after accepting requests. LedgerFlow models those situations without real-money integrations.

I chose a modular monolith to keep financial transactions local while enforcing package boundaries with ArchUnit. PostgreSQL owns ledger entries, balance projections, business states, idempotency records, audit and the outbox. A restricted posting function locks accounts in sorted UUID order, validates balances and writes balanced immutable journals. Java application services own explicit transactional commands; JPA is used where useful and JDBC exposes the locking/queue SQL.

The hardest correctness boundaries are concurrent spending, duplicate command arbitration and crash recovery. A real PostgreSQL test releases 100 concurrent transfer requests against a wallet that can fund only ten; exactly ten succeed and all journals remain balanced. Another sends twenty identical-key requests and verifies one transfer. Outbox publication is at least once, with persistent consumer markers and fenced worker leases. Refund locks prevent cumulative over-refunding. Redis limits abuse without holding financial truth.

The dashboard supports payments, refunds, wallets, ledger, keys, webhooks and operations. Tests use PostgreSQL/Kafka/Redis containers and a real HTTP receiver; Chromium verifies the packaged application. Metrics and durable tracing support diagnosis. This is a tested simulation and portfolio implementation, not a claim of production traffic or compliance.

## Deep dives grounded in the code

### Accounting and projections

A wallet is a liability: a credit increases the platform's obligation, a debit decreases it. Funding debits a cash-control asset and credits the wallet. A transfer debits one wallet liability and credits another. The database defers its balanced-journal check until commit, allowing individual entries to be inserted inside the transaction but never an unbalanced committed journal. Entries/journals cannot be edited or deleted, and creation transaction IDs reject appending to old journals. Corrections post new compensating entries. Reconciliation derives balances from entries and compares the projection.

Integer minor units avoid floating-point arithmetic and make PostgreSQL checks straightforward. INR has exponent two in the supported currency catalog; the system does not assume arbitrary currencies share that exponent. Per-command limits fit signed bigint; aggregation uses numeric and API strings. Adding FX needs explicit rates, rounding, clearing and gain/loss accounts, not merely accepting another currency string.

### Transactions and concurrency

A financial command claims idempotency, locks the payment if relevant, locks all accounts in sorted order, validates funds, writes business/journal/projection/audit/outbox and stores the response. A process crash before commit rolls everything back; a lost response after commit is resolved by same-key replay. Account locks serialize hot wallets. READ COMMITTED is sufficient because the read/decision occurs after locking. Optimistic locking would create avoidable retries under contention, and Redis locks would add another failure domain without improving a single-database invariant.

Payment refunds first lock the payment row, then account rows. This serializes cumulative refund limits and settlement liquidity. The common ordering avoids an account-to-payment inversion. Database lock/statement timeouts bound waiting. Clients reuse keys for uncertain outcomes; the application does not blindly repeat a new financial command.

### Idempotency

The unique tenant/operation/key row arbitrates concurrent callers through PostgreSQL conflict waiting. Canonical JSON fingerprints ignore property order but include behavior-changing DTO fields. Same key/body replays the original response; a changed request returns 409. Authorization precedes replay. A rolled-back attempt leaves no independently committed in-progress claim. After the 30-day response window, the key remains reserved; automatic payload archival is still future work. Different keys intentionally describe distinct commands.

### Outbox and Kafka

The outbox solves the database/broker dual-write gap by storing intent in the same commit as money movement. A worker claims ten rows using leases and SKIP LOCKED, sends outside the transaction and records success with a fence. It refuses a later aggregate version while an earlier version remains unpublished. A crash after a successful send still allows duplicates; a lease fence cannot retract an external send.

The consolidated versioned events topic uses aggregate message keys and six partitions in the example. Ordering is within a partition, not global. Separate consumer groups materialize notifications and webhook jobs. Consumer effects and processed-event markers commit together before offset acknowledgement. Poison records go to a DLT after confirmed publication; terminal inbox persistence retries database failure instead of recursively dead-lettering itself. DLT metadata is visible, but an automated replay API is not implemented.

### Webhook delivery

Kafka consumption schedules durable delivery jobs rather than blocking payment completion. Endpoints subscribe to event types; one endpoint/event job survives duplicate Kafka records. Workers restore stored payload/context, decrypt the secret and sign timestamp plus raw bytes. Bounded DNS/connect/read deadlines, public-address checks, pinned connections and TLS hostname verification defend the outbound boundary. Retry backoff with jitter runs for roughly a day before failure; manual replay preserves the event ID and attempt history. A merchant must persistently deduplicate events because an HTTP timeout cannot establish whether it accepted the request.

### Reconciliation

The worker claims a run, checks journal balance, projections, posting links, duplicate/orphan business records and refund sums in a REPEATABLE READ snapshot, then persists findings and completion together. Concurrent healthy writes do not create false mismatches between queries. Corruption tests use privileged fixture changes and verify detection, restoring data afterward. The engine never edits history. It is a bounded full scan; large retained datasets require incremental checkpoints with an accounting cutoff.

### Redis and observability

Redis holds atomic TTL rate counters only. Verified principal IDs and socket IPs identify budgets; forwarded headers are not trusted. Timeout returns 503 before business work with a short retry-suppression window. Restart can reset an abuse budget, but not a balance or idempotency claim. This trades API availability for a simple explicit abuse policy; health checks remain independent.

Metrics distinguish committed event outcomes from rolled-back work, and stale database backlog gauges carry availability/refresh time. HTTP/JVM/pool metrics are measured, without merchant/payment labels. Trace context is persisted with outbox/delivery jobs and restored across worker execution. Live HTTP/ledger/outbox spans have been queried from Tempo and Kafka context continuity is integration-tested. Individual JDBC statement spans and a visually inspected complete exported merchant-webhook trace are not claimed.

## Tradeoff questions

| Question | Implementation-specific answer |
| --- | --- |
| Why PostgreSQL? | Multi-row ACID, ordered row locks, deferred constraints, foreign keys and explicit SQL let one database enforce the financial commit. JSON flexibility alone would not justify weakening those invariants. |
| Why Kafka rather than RabbitMQ? | The implementation demonstrates a retained event log, independent consumer groups and aggregate-key partition ordering. RabbitMQ would be reasonable for delivery work queues; Kafka does not itself make financial posting reliable. |
| Why Redis? | Shared short-lived abuse counters across replicas. It is intentionally absent from balance/idempotency correctness. |
| Why a modular monolith? | One deployment keeps financial ACID simple while feature packages and architecture tests preserve extraction boundaries. Operational complexity should follow a measured need. |
| Why pessimistic locks? | Same-wallet contention is expected and a fresh serialized balance check is simple. Optimistic retries remain a valid alternative for lower-contention aggregates. |
| Why not SERIALIZABLE everywhere? | Explicit account/payment locks protect the relevant decisions with predictable contention. Serializable isolation would introduce additional abort/retry handling; it does not remove the need for idempotency. |
| Why outbox instead of publishing after commit? | A crash between commit and send would otherwise silently lose an event. Persisted intent makes eventual retry possible, subject to restoring dependencies and retention/disk capacity. |
| Why no exactly-once claim? | Database commit, Kafka acknowledgement and merchant HTTP acceptance are separate boundaries. Deduplication guarantees local effects per event, while external delivery remains at least once. |
| What fails first as traffic grows? | Measure first; hot settlement/account locks, PostgreSQL WAL/IO and connection budgets are plausible limits before stateless HTTP CPU. The current worker batch sizes also bound delivery throughput. |
| How would 100k TPS work? | It has not been demonstrated. It would likely require workload partitioning/sharding, many independent accounts, routing, outbox redesign and incremental reconciliation, with explicit cross-shard transfer semantics. More pods alone cannot scale one locked account. |
| Which service would you extract first? | Webhook/notification workers because they already operate across a durable event boundary. Ledger/payment extraction would require redesigning the currently atomic financial protocol. |
| How do you make the ledger highly available? | Managed PostgreSQL HA, tested failover, backups/restore and capacity planning. Failover can still yield ambiguous client outcomes, so preserve idempotency and acknowledge replication/RPO tradeoffs. No restore exercise is claimed here. |
| How would database sharding work? | Tenant-local accounts are a starting partition key. Cross-shard transfer needs reservations/clearing and durable orchestration with explicit intermediate states; do not pretend the current ACID function spans shards. |
| Why not event sourcing for the whole application? | The immutable journal is the financial truth, while business states/projections have explicit models. Rebuilding all identity/configuration state from events would add complexity without a present requirement. |
| Why JDBC alongside JPA? | JPA handles identity entities; explicit SQL is clearer for ordered locks, SECURITY DEFINER posting, SKIP LOCKED claims and analytics. Both participate in the same transaction manager. |
| Why no distributed locks? | PostgreSQL already owns the invariant. Redis lock expiry/network partitions would complicate correctness without replacing database constraints. |
| What remains before public production use? | Real identity/payment requirements, reviewed proxy/edge security, key rotation/recovery, managed-provider configuration, restore/failover drills, retention/replay tooling, deployment scans and measured capacity. The simulation must not be represented as a bank system. |

## Evidence to show in an interview

Open WalletIntegrationTest for the 100-request race and duplicate-key test; V3 migration for journal guards and sorted posting locks; Idempotency for conflict arbitration; OutboxQueue/Dispatcher for lease fencing; MessagingIntegrationTest for duplicates/outage/DLT; WebhookIntegrationTest for real HTTP failures and signatures; ReconciliationIntegrationTest for snapshot/corruption cases; RateLimitIntegrationTest for Redis failure; and frontend/e2e/financial.spec.ts for a real browser journey. Explain what each test proves and what it cannot prove about production scale.
