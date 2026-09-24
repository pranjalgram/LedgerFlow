# ADR-002: PostgreSQL owns financial truth

Status: accepted design, 2026-09-25. Implementation pending.

## Context

Money invariants require durable constraints, atomicity and coordination under contention.

## Decision

PostgreSQL primary holds immutable entries and derived balances; Flyway owns schema, Hibernate validates. Use explicit SQL where locks/constraints matter.

## Alternatives

Redis authority; document database; event log as sole storage.

## Consequences and tradeoffs

Strong local transactions and SQL reconciliation; primary writes/hot rows constrain throughput. Fail rather than accept financial commands without the database.
