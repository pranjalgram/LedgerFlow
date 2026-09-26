# ADR-011: Fail closed for all API routes during a Redis outage

Status: implemented; supersedes the authenticated-read fallback in ADR-008.

## Context

The initial design proposed allowing authenticated reads while Redis was unavailable. Those reads still consume authentication/database capacity and require a second fallback limiter with different guarantees. No measured availability requirement justifies that complexity for this simulation.

## Decision

Apply atomic fixed-window Redis limits before authentication and to verified principals afterward. On timeout, return 503 before business processing and suppress another Redis attempt for one second per process. Health/metrics routes remain independent. Financial state and persistent idempotency remain in PostgreSQL.

## Alternatives

Fail open, per-process emergency limits, or a managed edge limiter. These can improve availability but change abuse guarantees or introduce another policy to operate.

## Consequences and tradeoffs

Redis outages temporarily deny API reads and writes even when PostgreSQL is healthy. Existing asynchronous jobs can continue. Recovery needs no ledger repair; clients retain their original idempotency keys. Redis restart can reset the abuse window, so this is not a financial correctness boundary. A future edge service can replace the mechanism after explicit failure-policy tests.
