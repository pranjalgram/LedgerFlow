package com.ledgerflow.reconciliation;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.shared.Cursor;
import com.ledgerflow.shared.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationRuns {
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    private final AuditLog audit;
    public ReconciliationRuns(JdbcTemplate jdbc, MerchantAccess access, AuditLog audit) { this.jdbc = jdbc; this.access = access; this.audit = audit; }
    public record Run(UUID id, String status, Instant createdAt, Instant startedAt, Instant completedAt,
                      Instant snapshotAt, long recordsProcessed, long discrepancies, Long durationMs, String error) { }
    public record Finding(UUID id, String code, UUID resourceId, String expected, String actual) { }
    public record Findings(List<Finding> items, UUID nextCursor) { }
    @Transactional
    public UUID create(UUID merchant) {
        var actor = access.require(merchant); actor.requireManagement(); UUID id = UUID.randomUUID();
        jdbc.update("insert into reconciliation_run(id,merchant_id) values (?,?)", id, merchant);
        audit.append(merchant, actor.actorId(), "reconciliation.requested", id); return id;
    }
    @Transactional(readOnly = true)
    public Cursor.Page<Run> list(UUID merchant, int limit, String cursor) {
        access.require(merchant); var key = Cursor.parse(cursor);
        var rows = jdbc.query("select * from reconciliation_run where merchant_id=? and (created_at,id)<(?,?) order by created_at desc,id desc limit ?",
                (rs, row) -> row(rs), merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, value -> new Cursor(value.createdAt(), value.id()));
    }
    @Transactional(readOnly = true)
    public Run get(UUID merchant, UUID id) {
        access.require(merchant);
        return jdbc.query("select * from reconciliation_run where merchant_id=? and id=?", (rs, row) -> row(rs), merchant, id)
                .stream().findFirst().orElseThrow(() -> new DomainException(404, "reconciliation-not-found", "The reconciliation run was not found."));
    }
    @Transactional(readOnly = true)
    public Findings findings(UUID merchant, UUID id, int limit, UUID cursor) {
        get(merchant, id);
        var rows = jdbc.query("select id,code,resource_id,expected,actual from reconciliation_discrepancy where merchant_id=? and run_id=? and id>? order by id limit ?",
                (rs, row) -> new Finding(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5)),
                merchant, id, cursor == null ? new UUID(0, 0) : cursor, Cursor.limit(limit) + 1);
        boolean more = rows.size() > limit; var items = more ? rows.subList(0, limit) : rows;
        return new Findings(List.copyOf(items), more ? items.getLast().id() : null);
    }
    private static Run row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(rs.getObject("id", UUID.class), rs.getString("status"), time(rs, "created_at"), time(rs, "started_at"), time(rs, "completed_at"),
                time(rs, "snapshot_at"), rs.getLong("records_processed"), rs.getLong("discrepancies"), rs.getObject("duration_ms", Long.class), rs.getString("error_code"));
    }
    private static Instant time(java.sql.ResultSet rs, String name) throws java.sql.SQLException { var value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
}
