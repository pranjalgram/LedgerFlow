# ADR-005: Transactional polling outbox

Status: accepted and implemented; see IMPLEMENTATION.md for verification gates and documented refinements.

## Context

Committing a payment and publishing independently creates a lost-event crash window.

## Decision

Insert event with business transaction. Claim with SKIP LOCKED/lease, publish outside DB transaction, fence state update, sequence per aggregate.

## Alternatives

After-commit publish only; Debezium CDC; Modulith publication registry alongside custom outbox.

## Consequences and tradeoffs

Recoverable intent with duplicate publication possible. Polling adds DB load; CDC may replace transport later. One outbox avoids competing recovery mechanisms.
