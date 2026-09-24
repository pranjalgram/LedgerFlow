# Scaling hypothesis

These stages are design discussion, not measured throughput claims. Workload shape, hot accounts, storage latency, transaction sizes, webhook endpoints and hardware dominate capacity. Run the supplied future load scripts and retain environment details before making performance claims.

| Approximate workload | Evolution to evaluate |
| --- | --- |
| 10 requests/s | One API/worker deployment, bounded Hikari pool, single PostgreSQL primary, small Redis/Kafka instances; prioritize correctness and restore tests. |
| 1,000 requests/s | Stateless API replicas, explicit connection budget across replicas, index/plan measurement, bounded outbox/webhook worker concurrency, Kafka groups scaled up to partitions; read replicas only for stale-tolerant history/analytics. |
| 10,000 requests/s | Profile WAL/IO and hot settlement locks; separate worker deployments from API without changing modules, batch outbox claims, partition append-only history by time where uniqueness/FKs permit, incremental reconciliation checkpoints, archive cold history, limit trace/log cost. |
| 100,000+ requests/s | Requires measured redesign: tenant/account sharding, many independent posting partitions, routing and shard ownership, CDC outbox evaluation, Kafka partition migration, Redis cluster, distributed reconciliation. Global hot-account serialization cannot scale merely by adding pods. |

Never serve authoritative spend decisions from read replicas. Replicas can lag; response UI may need read-your-writes routing. Total pools must fit database capacity; virtual threads do not create database throughput. Connection multiplexing requires careful transaction/session settings.

Account sharding makes cross-shard transfers a different protocol: reservation/clearing accounts and durable orchestration, explicit intermediate states, reconciliation, and no misleading ACID claim. Keep a payment's customer/settlement accounts co-located by tenant initially. Partitioned financial tables complicate global uniqueness and FKs; do not add partitioning prematurely.

Extract webhook/notification workers first when independent scaling or failure isolation warrants it. Extract ledger/payment only with stable contracts, team ownership and a specified consistency protocol. Kafka events do not magically replace the current atomic posting transaction. Managed PostgreSQL with tested failover/backups and managed Kafka/Redis are usually preferable to operating all stateful services inside the application cluster. HA does not remove ambiguous commits or replace idempotency.
