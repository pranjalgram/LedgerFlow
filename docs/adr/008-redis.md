# ADR-008: Redis only for rate limiting initially

Status: accepted and implemented; see IMPLEMENTATION.md for verification gates and documented refinements.

## Context

Shared rate limits are useful; authoritative funds and idempotency cannot disappear with a cache.

## Decision

Atomic TTL counters; no financial locks or balances. The initial read-fallback proposal is superseded by ADR-011: all API requests fail closed during a Redis outage.

## Alternatives

In-process-only counters; Redis balance source; omit rate limiting.

## Consequences and tradeoffs

Shared limits across replicas with explicit availability cost. PostgreSQL remains financially correct when Redis disappears. A gateway limiter could replace Redis later.
