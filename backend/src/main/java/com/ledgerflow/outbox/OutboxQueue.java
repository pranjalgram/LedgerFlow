package com.ledgerflow.outbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxQueue {
    private final JdbcTemplate jdbc;
    public OutboxQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Claimed(UUID id, UUID merchant, String aggregateType, UUID aggregateId, String payload, UUID leaseToken, int attempts, String traceContext) {
        public String messageKey() { return merchant + ":" + aggregateType + ":" + aggregateId; }
    }

    @Transactional
    public List<Claimed> claim() {
        return jdbc.query("""
                with eligible as (
                  select e.id from outbox_event e where e.published_at is null
                  and ((e.state='PENDING' and e.next_attempt_at<=now()) or (e.state='IN_FLIGHT' and e.lease_until<=now()))
                  and not exists (select 1 from outbox_event previous where previous.aggregate_type=e.aggregate_type
                    and previous.aggregate_id=e.aggregate_id and previous.published_at is null
                    and (previous.aggregate_version,previous.event_index)<(e.aggregate_version,e.event_index))
                  order by e.created_at,e.id limit 10 for update skip locked
                )
                update outbox_event e set state='IN_FLIGHT',lease_token=gen_random_uuid(),lease_until=now()+interval '60 seconds',attempts=attempts+1
                from eligible where e.id=eligible.id returning e.id,e.merchant_id,e.aggregate_type,e.aggregate_id,e.payload,e.lease_token,e.attempts,e.trace_context
                """, (rs, row) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, UUID.class), rs.getInt(7), rs.getString(8)));
    }

    @Transactional
    public boolean published(Claimed event) {
        return jdbc.update("update outbox_event set state='PUBLISHED',published_at=now(),lease_token=null,lease_until=null,last_error_code=null where id=? and lease_token=? and state='IN_FLIGHT'",
                event.id(), event.leaseToken()) == 1;
    }

    @Transactional
    public void retry(Claimed event, String error) {
        long delay = Math.min(300, 1L << Math.min(event.attempts() - 1, 8));
        long milliseconds = delay * 1000 + ThreadLocalRandom.current().nextLong(500);
        jdbc.update("update outbox_event set state='PENDING',next_attempt_at=now()+(? * interval '1 millisecond'),lease_token=null,lease_until=null,last_error_code=? where id=? and lease_token=?",
                milliseconds, error, event.id(), event.leaseToken());
    }

    @Transactional
    public void block(Claimed event) {
        jdbc.update("update outbox_event set state='BLOCKED',lease_token=null,lease_until=null,last_error_code='invalid-envelope' where id=? and lease_token=?",
                event.id(), event.leaseToken());
    }
}
