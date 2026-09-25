package com.ledgerflow.outbox.internal;

import com.ledgerflow.outbox.EventCodec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "ledgerflow.events.enabled", havingValue = "true")
class DeadLetterListener {
    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    DeadLetterListener(JdbcTemplate jdbc, EventCodec codec) { this.jdbc = jdbc; this.codec = codec; }

    @KafkaListener(topics = "${ledgerflow.events.topic}.dlt", groupId = "ledgerflow-dlt-inspector-v1", containerFactory = "deadLetterContainerFactory")
    @Transactional
    public void receive(ConsumerRecord<String, String> record) {
        UUID merchant = null; UUID eventId = null;
        try {
            var event = codec.decode(record.value());
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from merchant where id=?)", Boolean.class, event.merchantId()));
            if (exists) merchant = event.merchantId();
            eventId = event.id();
        } catch (EventCodec.InvalidEvent invalid) {
            // Invalid envelopes belong to platform operators, never to an inferred tenant.
        }
        String payload = record.value() == null ? "null" : record.value();
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65536) payload = "[oversized payload omitted; inspect Kafka DLT]";
        jdbc.update("insert into dead_letter(id,merchant_id,event_id,source_topic,source_partition,source_offset,payload) values (?,?,?,?,?,?,?) on conflict do nothing",
                UUID.randomUUID(), merchant, eventId, record.topic(), record.partition(), record.offset(), payload);
    }
}
