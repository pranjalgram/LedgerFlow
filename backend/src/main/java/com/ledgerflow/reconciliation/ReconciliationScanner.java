package com.ledgerflow.reconciliation;

import com.ledgerflow.audit.AuditLog;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationScanner {
    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    public ReconciliationScanner(JdbcTemplate jdbc, AuditLog audit) { this.jdbc = jdbc; this.audit = audit; }
    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public void scan(ReconciliationQueue.Job job) {
        long start = System.nanoTime();
        var owned = jdbc.query("select id from reconciliation_run where id=? and merchant_id=? and lease_token=? and status='RUNNING' for update",
                (rs, row) -> rs.getObject(1), job.id(), job.merchant(), job.lease());
        if (owned.isEmpty()) return;
        jdbc.execute("set local statement_timeout='25s'");
        long findings = jdbc.update("""
                insert into reconciliation_discrepancy(id,merchant_id,run_id,code,resource_id,expected,actual)
                select gen_random_uuid(),?,?,code,resource_id,expected,actual from reconciliation_findings(?)
                """, job.merchant(), job.id(), job.merchant());
        Long records = jdbc.queryForObject("""
                select (select count(*) from ledger_account where merchant_id=?) + (select count(*) from ledger_transaction where merchant_id=?)
                     + (select count(*) from ledger_entry where merchant_id=?) + (select count(*) from payment where merchant_id=?)
                     + (select count(*) from refund where merchant_id=?) + (select count(*) from transfer where merchant_id=?)
                     + (select count(*) from funding where merchant_id=?)
                """, Long.class, job.merchant(), job.merchant(), job.merchant(), job.merchant(), job.merchant(), job.merchant(), job.merchant());
        jdbc.update("""
                update reconciliation_run set status=?,completed_at=clock_timestamp(),snapshot_at=transaction_timestamp(),
                  records_processed=?,discrepancies=?,duration_ms=?,lease_token=null,lease_until=null where id=? and lease_token=?
                """, findings == 0 ? "PASSED" : "DISCREPANCIES", records, findings, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), job.id(), job.lease());
        audit.append(job.merchant(), null, findings == 0 ? "reconciliation.passed" : "reconciliation.discrepancies", job.id());
    }
}
