package com.ledgerflow.webhook;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookQueue {
    private final JdbcTemplate jdbc;
    public WebhookQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Job(UUID id, UUID endpointId, UUID eventId, String payload, String url, String encryptedSecret,
                      UUID lease, int attempt, int cycleAttempt, boolean enabled) { }
    @Transactional
    public List<Job> claim() {
        return jdbc.query("""
                with eligible as (
                  select id from webhook_delivery where (state='PENDING' and next_attempt_at<=now())
                    or (state='IN_FLIGHT' and lease_until<=now())
                  order by next_attempt_at,id limit 5 for update skip locked
                ), claimed as (
                  update webhook_delivery d set state='IN_FLIGHT',attempts=attempts+1,cycle_attempts=cycle_attempts+1,
                    lease_token=gen_random_uuid(),lease_until=now()+interval '60 seconds'
                  from eligible where d.id=eligible.id returning d.*
                ) select d.id,d.endpoint_id,d.event_id,d.payload,e.url,e.encrypted_secret,d.lease_token,d.attempts,d.cycle_attempts,e.enabled
                from claimed d join webhook_endpoint e on e.id=d.endpoint_id
                """, (rs, row) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getInt(8), rs.getInt(9), rs.getBoolean(10)));
    }
    @Transactional
    public boolean finish(Job job, WebhookTransport.Result result) {
        boolean terminal = result.succeeded() || job.cycleAttempt() >= 32 || !job.enabled();
        long seconds = Math.min(3600, 60L << Math.min(job.cycleAttempt() - 1, 6)) + ThreadLocalRandom.current().nextLong(30);
        int updated = jdbc.update("""
                update webhook_delivery set state=?,next_attempt_at=now()+(? * interval '1 second'),
                    lease_token=null,lease_until=null,completed_at=case when ? then now() else null end,last_http_status=?,last_error=?
                where id=? and lease_token=? and state='IN_FLIGHT'
                """, result.succeeded() ? "SUCCEEDED" : terminal ? "FAILED" : "PENDING", seconds, terminal,
                result.status(), result.error(), job.id(), job.lease());
        if (updated == 0) return false;
        jdbc.update("insert into webhook_attempt(delivery_id,attempt,http_status,error_code,duration_ms) values (?,?,?,?,?)",
                job.id(), job.attempt(), result.status(), result.error(), result.durationMs());
        return true;
    }
}
