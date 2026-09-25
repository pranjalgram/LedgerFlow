package com.ledgerflow.notification;

import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.outbox.Outbox;
import com.ledgerflow.outbox.ProcessedEvents;
import com.ledgerflow.shared.Cursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    public static final String CONSUMER = "ledgerflow-notifications-v1";
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final MerchantAccess access;
    public NotificationService(JdbcTemplate jdbc, ProcessedEvents processed, MerchantAccess access) { this.jdbc = jdbc; this.processed = processed; this.access = access; }

    @Transactional
    public void consume(Outbox.Event event) {
        if (!processed.first(CONSUMER, event.id())) return;
        jdbc.update("insert into notification(id,merchant_id,event_id,kind,resource_id) values (?,?,?,?,?) on conflict do nothing",
                UUID.randomUUID(), event.merchantId(), event.id(), event.type(), event.aggregateId());
    }

    public record Notification(UUID id, UUID eventId, String kind, UUID resourceId, Instant createdAt) { }
    @Transactional(readOnly = true)
    public Cursor.Page<Notification> list(UUID merchant, int limit, String cursor) {
        access.require(merchant); var key = Cursor.parse(cursor);
        var rows = jdbc.query("select id,event_id,kind,resource_id,created_at from notification where merchant_id=? and (created_at,id)<(?,?) order by created_at desc,id desc limit ?",
                (rs, row) -> new Notification(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant()),
                merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }
}
