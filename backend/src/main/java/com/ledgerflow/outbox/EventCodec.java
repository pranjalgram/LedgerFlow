package com.ledgerflow.outbox;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventCodec {
    private final ObjectMapper json;
    public EventCodec(ObjectMapper json) { this.json = json; }

    public static final class InvalidEvent extends RuntimeException {
        private static final long serialVersionUID = 1L;
        InvalidEvent() { super("Event does not satisfy the v1 envelope contract"); }
    }

    public Outbox.Event decode(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 65536) throw new InvalidEvent();
        Outbox.Event event;
        try { event = json.readValue(value, Outbox.Event.class); }
        catch (JacksonException invalid) { throw new InvalidEvent(); }
        if (event == null || event.id() == null || event.merchantId() == null || event.aggregateId() == null
                || event.schemaVersion() != 1 || event.aggregateVersion() < 1 || event.eventIndex() < 0
                || event.occurredAt() == null || event.data() == null || event.type() == null
                || !event.type().matches("[a-z]+\\.[a-z_.]+") || event.type().length() > 100
                || event.aggregateType() == null || !event.aggregateType().matches("[a-z]{1,40}")) throw new InvalidEvent();
        return event;
    }
}
