# ADR-006: Ordered pessimistic account locks

Status: accepted design, 2026-09-25. Implementation pending.

## Context

Concurrent spend checks must not both use the same starting balance.

## Decision

READ COMMITTED plus account locks sorted by UUID, payment lock before accounts for capture/refunds, common posting function and bounded timeouts.

## Alternatives

Optimistic version retries; SERIALIZABLE everywhere; Redis distributed locks.

## Consequences and tradeoffs

Simple proof under contention, but hot accounts serialize and waiters consume capacity. Optimistic locking suits low contention but adds retries here. Redis cannot replace database enforcement.
