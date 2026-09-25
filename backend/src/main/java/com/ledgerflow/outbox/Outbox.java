package com.ledgerflow.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class Outbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.ledgerflow.shared.Telemetry telemetry;
    public Outbox(JdbcTemplate jdbc, ObjectMapper json, com.ledgerflow.shared.Telemetry telemetry) { this.jdbc = jdbc; this.json = json; this.telemetry = telemetry; }

    public record Event(UUID id, int schemaVersion, String type, Instant occurredAt, UUID merchantId,
                        String aggregateType, UUID aggregateId, long aggregateVersion, int eventIndex,
                        String correlationId, Map<String, Object> data) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(UUID merchant, String type, String aggregateType, UUID aggregateId, long version, Map<String, Object> data) {
        UUID id = UUID.randomUUID();
        var event = new Event(id, 1, type, Instant.now(), merchant, aggregateType, aggregateId, version, 0,
                MDC.get("requestId"), Map.copyOf(data));
        jdbc.update("""
                insert into outbox_event(id,merchant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,trace_context)
                values (?,?,?,?,?,?,?::jsonb,?)
                """, id, merchant, aggregateType, aggregateId, version, type, json.writeValueAsString(event), telemetry.capture());
        telemetry.committedEvent(type);
        return id;
    }
}
