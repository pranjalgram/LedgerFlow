package com.ledgerflow.operations;

import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.shared.Cursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueries {
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    public AuditQueries(JdbcTemplate jdbc, MerchantAccess access) { this.jdbc = jdbc; this.access = access; }
    public record Event(UUID id, UUID actorId, String action, UUID resourceId, String correlationId, Instant occurredAt) { }
    @Transactional(readOnly = true)
    public Cursor.Page<Event> list(UUID merchant, int limit, String cursor) {
        access.require(merchant); var key = Cursor.parse(cursor);
        var rows = jdbc.query("select id,actor_id,action,resource_id,correlation_id,occurred_at from audit_event where merchant_id=? and (occurred_at,id)<(?,?) order by occurred_at desc,id desc limit ?",
                (rs, row) -> new Event(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), rs.getTimestamp(6).toInstant()),
                merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.occurredAt(), row.id()));
    }
}
