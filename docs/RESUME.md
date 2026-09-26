# Resume bullets

These describe this repository's verified simulation, not employment experience or production traffic. Select the bullets relevant to the role.

- Built a Java 25/Spring Boot payment platform with an immutable PostgreSQL double-entry ledger, restricted posting functions and atomic balance, audit and outbox updates.
- Verified spending correctness with 100 concurrent transfer requests against a wallet funding only ten; enforced persistent request idempotency, tenant isolation and serialized partial-refund limits using real PostgreSQL tests.
- Implemented Kafka transactional-outbox delivery, persistent consumer deduplication and signed asynchronous webhooks with fenced leases, bounded retries, attempt history and replay.
- Delivered a React/TypeScript merchant dashboard, snapshot reconciliation, Redis rate limits and protected metrics/tracing; validated 46 real-infrastructure integration tests and a packaged Chromium financial flow, with Docker Compose and Helm deployment examples.

No user counts, capacity, latency improvements, availability guarantees or production adoption are claimed. The short k6 smoke run is not a resume throughput benchmark. Update test counts if the suite changes; see IMPLEMENTATION.md for evidence and README.md for remaining limits.
