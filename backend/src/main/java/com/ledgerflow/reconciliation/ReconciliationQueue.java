package com.ledgerflow.reconciliation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationQueue {
    private final JdbcTemplate jdbc;
    public ReconciliationQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Job(UUID id, UUID merchant, UUID lease) { }
    @Transactional
    public Optional<Job> claim() {
        jdbc.update("update reconciliation_run set status='FAILED',completed_at=now(),lease_token=null,lease_until=null,error_code='worker-retry-exhausted' where status='RUNNING' and lease_until<=now() and attempts>=3");
        return jdbc.query("""
                with eligible as (select id from reconciliation_run where status='PENDING' or (status='RUNNING' and lease_until<=now())
                  order by created_at,id limit 1 for update skip locked)
                update reconciliation_run r set status='RUNNING',started_at=now(),attempts=attempts+1,lease_token=gen_random_uuid(),lease_until=now()+interval '2 minutes'
                from eligible where r.id=eligible.id returning r.id,r.merchant_id,r.lease_token
                """, (rs, row) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class))).stream().findFirst();
    }
    @Transactional
    public void failed(Job job) {
        jdbc.update("update reconciliation_run set status='FAILED',completed_at=now(),lease_token=null,lease_until=null,error_code='scan-database-error' where id=? and lease_token=? and status='RUNNING'", job.id(), job.lease());
    }
}
