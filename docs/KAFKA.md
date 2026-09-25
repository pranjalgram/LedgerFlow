# Kafka and event contracts

Use `ledgerflow.events.v1` (6 partitions locally/configurable, replication factor 1 local and 3 deployment target) and `ledgerflow.events.v1.dlt`. Production producer: acks=all, enable.idempotence=true; broker target min.insync.replicas=2. Single-node local durability is intentionally weaker. Retention starts at 7 days, subject to storage budgeting. No topic per event type and no Kafka transaction claim spanning PostgreSQL.

Key: `merchantId:aggregateType:aggregateId`. A stable key orders that aggregate in one partition, provided the outbox publishes its sequence in order. Changing partition count can move keys; drain/repartition with a migration plan rather than assuming ordering survives.

```json
{
  "id": "a21e6e0e-2294-4dc4-9bfd-1cd14ed5c365",
  "schemaVersion": 1,
  "type": "payment.succeeded",
  "occurredAt": "2026-09-25T00:00:00Z",
  "merchantId": "7fa8e235-2e8e-4a0a-9d7a-0ad53464ef41",
  "aggregateType": "payment",
  "aggregateId": "23c02be8-7bf3-4cb0-b523-8c6ea3fdb3cd",
  "aggregateVersion": 2,
  "eventIndex": 0,
  "correlationId": "d5f5f4ed-b670-4085-92bc-62fb66ee962c",
  "data": {"amountMinor": 49900, "currency": "INR", "ledgerTransactionId": "e0145b71-dbe6-4a0f-a94a-6c9b91e78ebf"}
}
```

Event types: payment.created/processing/succeeded/failed/cancelled, refund.succeeded/failed, transfer.completed, wallet.funded, ledger.transaction.posted. Refund creation/success are synchronous; no artificial long-lived processing event is required. Audit is written locally in command transactions; only publish audit events when a downstream consumer actually exists. Event payloads omit credentials, arbitrary customer PII, and unrestricted metadata. Schema changes are additive within v1; incompatible changes need a new schema/topic migration and compatibility tests. Commit JSON Schema contracts when implementation begins.

Consumer groups `ledgerflow-webhooks-v1` and `ledgerflow-notifications-v1` independently consume. Each local effect transaction begins with `(consumer_name,event_id)` insert into ProcessedEvent ON CONFLICT DO NOTHING. Duplicate means success with no side effects. Acknowledge offset only after DB commit. A crash before commit rolls back both marker and effects; after commit/before offset causes harmless replay. Retain markers at least as long as all replayable events; initially indefinitely, archive only with coordinated replay floor.

Use bounded blocking retries for transient database failures, with backoff below max.poll.interval and partition pause/backpressure when needed. Malformed/unsupported poison events go to DLT with original payload and sanitized error metadata. Commit offset only after DLT send acknowledgement; DLT failure must stop advancement. DLT replay is authenticated and audited and preserves original event ID. Skipping poison events means downstream event arrival may have gaps; webhook scheduling uses independent immutable events and makes no state reconstruction claim.

Trace context travels as W3C traceparent/tracestate headers sourced from the persisted outbox context. New consumer spans link to the originating context; do not keep an HTTP span open while waiting for asynchronous delivery.

## Implemented consumers

`ledgerflow-notifications-v1` inserts a durable notification and a `(consumer_name,event_id)` marker in one database transaction. Record offsets are acknowledged only after successful return. The listener retries three times with exponential backoff, then publishes to the same partition in `.dlt` with confirmed send. Invalid envelopes go directly to DLT. The terminal DLT inspector persists records uniquely by Kafka coordinates and retries database outages indefinitely at five-second intervals instead of recursively dead-lettering. Stored coordinates currently identify the DLT record, not the original source record.

`GET /api/v1/notifications` provides tenant-authorized keyset pagination. DLT operator UI/replay and retention jobs are not yet implemented. Tracing propagation is scheduled for the observability phase.
