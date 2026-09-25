# Transactional outbox

Business state, ledger changes, audit, and outbox insertion commit in one PostgreSQL transaction. A rolled-back command produces none of them. The outbox stores an immutable event envelope; mutable delivery state is separate columns. Runtime code cannot edit envelope fields after insertion.

Poll eligible unpublished rows in small batches with `FOR UPDATE SKIP LOCKED`. Assign `lease_token`, `lease_until`, and increment attempts in a short transaction. Publish outside the transaction; mark published only after broker acknowledgement with `WHERE id=? AND lease_token=?`. Expired leases are reclaimable. Set producer delivery timeout below lease duration and renew if necessary. Fencing prevents an old worker from overwriting newer state; it cannot retract a message already accepted by Kafka.

Events carry aggregate ID/version and an event index where one transition emits multiple events. A unique `(aggregate_type, aggregate_id, aggregate_version, event_index)` identifies order. Eligibility excludes any event with an earlier unpublished sequence for the same aggregate, including leased/retrying predecessors. That preserves per-aggregate publication order; a poisoned predecessor deliberately stalls that aggregate and alerts. Kafka key includes tenant and aggregate. There is no global order or ordering across payment and ledger aggregates.

On transient broker failure release/reschedule with capped exponential backoff and jitter (1s base, 5m cap). Do not discard committed events after a maximum attempt count. Mark repeatedly failing payloads BLOCKED for intervention, expose backlog age/count, and provide audited retry after fixing the cause. Preserve event ID and payload on every publish/replay. Archive published rows after 30 days only if consumer/replay policy permits; unpublished events never age out.

Crash after DB commit but before publish: row remains. Crash after Kafka acknowledgement but before marking: row republishes, producing duplicate delivery. Consumer's ProcessedEvent unique key suppresses duplicate local effects in its own transaction. Kafka producer idempotence helps network retries within a producer session but cannot solve the cross-database crash window. Guarantee: eventual at-least-once publication while dependencies recover and blocked rows are resolved, not exactly-once delivery.

Database lease work and financial requests share a bounded pool initially; publisher concurrency is limited so Kafka outages cannot exhaust financial database capacity. A later worker deployment may run the same application with API scheduling disabled/enabled by explicit role configuration.

## Implemented worker

Enable `EVENTS_ENABLED=true` and set `KAFKA_BOOTSTRAP_SERVERS` after starting the Compose events profile. Disabled delivery leaves durable outbox rows pending. The worker claims ten rows with 60-second leases; each send waits at most five seconds. Broker errors reschedule with exponential delay (currently maximum 256 seconds plus jitter). Invalid envelopes block that aggregate. Administrative retry, backlog metrics and archival remain future operations work.

Normal first publication follows aggregate sequence. A stalled old publisher can still send a duplicate after its lease expires; fencing protects database state, not Kafka. Consumers must deduplicate IDs and any future state projection must reject stale versions. Notifications are immutable event facts, so they do not apply version-sensitive state updates.
