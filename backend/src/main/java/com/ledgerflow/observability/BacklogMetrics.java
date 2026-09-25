package com.ledgerflow.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
class BacklogMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong webhooks = new AtomicLong();
    private final AtomicLong discrepancies = new AtomicLong();
    private final AtomicLong refreshed = new AtomicLong();
    private final AtomicLong available = new AtomicLong();
    BacklogMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        meters.gauge("ledgerflow.outbox.pending", pending); meters.gauge("ledgerflow.outbox.blocked", blocked);
        meters.gauge("ledgerflow.webhook.failed", webhooks); meters.gauge("ledgerflow.reconciliation.problem_runs", discrepancies);
        meters.gauge("ledgerflow.backlog.refreshed_epoch_seconds", refreshed); meters.gauge("ledgerflow.backlog.available", available);
    }
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void refresh() {
        try {
            var values = jdbc.queryForMap("""
                    select (select count(*) from outbox_event where published_at is null) as pending,
                      (select count(*) from outbox_event where state='BLOCKED') as blocked,
                      (select count(*) from webhook_delivery where state='FAILED') as webhooks,
                      (select count(*) from reconciliation_run where status in ('FAILED','DISCREPANCIES')) as discrepancies
                    """);
            pending.set(((Number) values.get("pending")).longValue()); blocked.set(((Number) values.get("blocked")).longValue());
            webhooks.set(((Number) values.get("webhooks")).longValue()); discrepancies.set(((Number) values.get("discrepancies")).longValue());
            refreshed.set(java.time.Instant.now().getEpochSecond()); available.set(1);
        } catch (DataAccessException unavailable) { available.set(0); }
    }
}
