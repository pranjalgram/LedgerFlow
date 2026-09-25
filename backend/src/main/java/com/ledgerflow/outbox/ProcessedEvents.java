package com.ledgerflow.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessedEvents {
    private final JdbcTemplate jdbc;
    public ProcessedEvents(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean first(String consumer, UUID eventId) {
        return jdbc.update("insert into processed_event(consumer_name,event_id) values (?,?) on conflict do nothing", consumer, eventId) == 1;
    }
}
