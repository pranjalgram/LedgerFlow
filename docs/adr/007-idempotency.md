# ADR-007: Transactional persistent key claims

Status: accepted design, 2026-09-25. Implementation pending.

## Context

Clients retry after unknown responses and restarts; simultaneous identical commands must execute once.

## Decision

Tenant/operation/key unique claim, canonical request SHA-256, stored response in same transaction, full response 30 days then permanent compact tombstone.

## Alternatives

Redis SETNX only; separately committed IN_PROGRESS state; expiring and reusing financial keys.

## Consequences and tradeoffs

Atomic replay/rollback without stranded claims. More persistent storage and key-scope design. Expired response is conflict/resource lookup, never silent re-execution.
