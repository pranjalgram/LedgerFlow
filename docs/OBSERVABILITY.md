# Observability plan

Actuator liveness reports process health; readiness includes PostgreSQL and the ability to accept commands. Do not make liveness depend on Kafka/Redis and cause restart storms. Expose detailed dependency health only internally; public health reveals minimal status. Prometheus/Grafana/Tempo/Loki are an optional Compose profile so the normal development footprint remains small.

Use Micrometer HTTP/JVM/Hikari metrics, low-cardinality counters for financial outcomes and histograms for command latency, outbox backlog/oldest age, consumer failures/lag, webhook latency/retries/exhaustion, and reconciliation discrepancies. Never label metrics with merchant/payment/request IDs. Alert on oldest pending outbox age, DLT arrivals, exhausted webhooks, pool saturation and any financial discrepancy; thresholds are configuration, not invented SLO achievements.

Structured JSON logs carry service, level, request/correlation ID and trace/span ID. Use one sanitized summary per command/failure, not financial payload dumps. Persist trace context with outbox and inject Kafka headers. HTTP, application service and JDBC instrumentation feed OpenTelemetry; verify actual spans before claiming an end-to-end trace. Consumer/delivery spans link across asynchronous boundaries; retention and sampling are explicit operational costs.

Phase 11 must deliver provisioned dashboards and a documented real trace from HTTP through posting, outbox, Kafka and webhook. No dashboards or telemetry pipelines are implemented yet.
