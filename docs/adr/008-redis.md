# ADR-008: Redis only for rate limiting initially

Status: accepted design, 2026-09-25. Implementation pending.

## Context

Shared rate limits are useful; authoritative funds and idempotency cannot disappear with a cache.

## Decision

Atomic TTL counters for credential/mutation limits. Fail closed on sensitive routes, allow bounded authenticated reads on outage; no financial locks or balances.

## Alternatives

In-process-only counters; Redis balance source; omit rate limiting.

## Consequences and tradeoffs

Shared limits across replicas with explicit availability cost. PostgreSQL remains financially correct when Redis disappears. A gateway limiter could replace Redis later.
