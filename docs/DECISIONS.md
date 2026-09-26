# Architecture decision records

Records document implemented choices and explicit later refinements. See IMPLEMENTATION.md for verification evidence. Changes require a superseding ADR with the reason and migration implications.

| ADR | Decision |
| --- | --- |
| [001](adr/001-modular-monolith.md) | Modular monolith before microservices |
| [002](adr/002-postgresql.md) | PostgreSQL owns financial truth |
| [003](adr/003-ledger.md) | Immutable double-entry ledger |
| [004](adr/004-kafka.md) | At-least-once Kafka delivery |
| [005](adr/005-outbox.md) | Transactional polling outbox |
| [006](adr/006-concurrency.md) | Ordered pessimistic account locking |
| [007](adr/007-idempotency.md) | Persistent transactional key claims |
| [008](adr/008-redis.md) | Redis for rate limiting, no money state |
| [009](adr/009-authentication.md) | First-party credentials with Spring JWT validation |
| [010](adr/010-money.md) | Explicit currency and integer minor units |
| [011](adr/011-redis-outage.md) | Fail closed for all API routes on Redis outage; supersedes ADR-008 fallback |
