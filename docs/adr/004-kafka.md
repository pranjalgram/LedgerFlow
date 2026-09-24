# ADR-004: At-least-once event delivery

Status: accepted design, 2026-09-25. Implementation pending.

## Context

Webhook and notification work must not delay financial commits, and consumers may crash or replay.

## Decision

One versioned event topic keyed by aggregate, independent consumer groups, persistent processed IDs, acknowledged DLT forwarding.

## Alternatives

Synchronous HTTP; RabbitMQ work queues; Kafka exactly-once claim across PostgreSQL.

## Consequences and tradeoffs

Replayable fan-out with explicit duplicate handling and operational cost. RabbitMQ would also suit delivery queues, but retained event streams serve this project's replay/rebuild goal. Kafka transactions cannot atomically commit PostgreSQL effects.
