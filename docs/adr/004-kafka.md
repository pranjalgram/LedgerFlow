# ADR-004: At-least-once event delivery

Status: accepted and implemented; see IMPLEMENTATION.md for verification gates and documented refinements.

## Context

Webhook and notification work must not delay financial commits, and consumers may crash or replay.

## Decision

One versioned event topic keyed by aggregate, independent consumer groups, persistent processed IDs, acknowledged DLT forwarding.

## Alternatives

Synchronous HTTP; RabbitMQ work queues; Kafka exactly-once claim across PostgreSQL.

## Consequences and tradeoffs

Replayable fan-out with explicit duplicate handling and operational cost. RabbitMQ would also suit delivery queues, but retained event streams serve this project's replay/rebuild goal. Kafka transactions cannot atomically commit PostgreSQL effects.
