package com.ledgerflow.operations;

import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.shared.Cursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped read models compose module-owned SQL data without modifying financial state. */
@Service
public class OperationsQueries {
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    public OperationsQueries(JdbcTemplate jdbc, MerchantAccess access) { this.jdbc = jdbc; this.access = access; }
    public record Daily(LocalDate day, long payments) { }
    public record Overview(String currency, long paymentCount, long succeededCount, long failedCount,
                           String capturedMinor, String refundedMinor, String walletBalanceMinor, List<Daily> daily) { }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview(UUID merchant) {
        access.require(merchant);
        var totals = jdbc.queryForMap("""
                select count(*) as total,count(*) filter(where status in ('SUCCEEDED','REFUNDED','PARTIALLY_REFUNDED')) as succeeded,
                  count(*) filter(where status='FAILED') as failed,
                  coalesce(sum(amount_minor) filter(where status in ('SUCCEEDED','REFUNDED','PARTIALLY_REFUNDED')),0)::text as captured,
                  coalesce(sum(refunded_minor),0)::text as refunded from payment where merchant_id=? and currency='INR'
                """, merchant);
        String wallets = jdbc.queryForObject("select coalesce(sum(balance_minor),0)::text from ledger_account where merchant_id=? and purpose='WALLET' and currency='INR'", String.class, merchant);
        var days = jdbc.query("""
                select d.day::date,count(p.id) from generate_series((current_date-13)::timestamp,current_date::timestamp,interval '1 day') d(day)
                left join payment p on p.merchant_id=? and p.currency='INR' and p.created_at>=d.day and p.created_at<d.day+interval '1 day'
                group by d.day order by d.day
                """, (rs, row) -> new Daily(rs.getObject(1, LocalDate.class), rs.getLong(2)), merchant);
        return new Overview("INR", ((Number) totals.get("total")).longValue(), ((Number) totals.get("succeeded")).longValue(),
                ((Number) totals.get("failed")).longValue(), (String) totals.get("captured"), (String) totals.get("refunded"), wallets, days);
    }
    public record Health(long outboxPending, long outboxBlocked, long failedWebhooks, long deadLetters) { }
    @Transactional(readOnly = true)
    public Health health(UUID merchant) {
        access.require(merchant);
        return jdbc.queryForObject("""
                select (select count(*) from outbox_event where merchant_id=? and published_at is null),
                  (select count(*) from outbox_event where merchant_id=? and state='BLOCKED'),
                  (select count(*) from webhook_delivery where merchant_id=? and state='FAILED'),
                  (select count(*) from dead_letter where merchant_id=?)
                """, (rs, row) -> new Health(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), merchant, merchant, merchant, merchant);
    }
    public record Event(UUID id, String type, String state, int attempts, Instant createdAt, String error) { }
    @Transactional(readOnly = true)
    public Cursor.Page<Event> events(UUID merchant, int limit, String cursor) {
        access.require(merchant); var key = Cursor.parse(cursor);
        var rows = jdbc.query("select id,event_type,state,attempts,created_at,last_error_code from outbox_event where merchant_id=? and published_at is null and (created_at,id)<(?,?) order by created_at desc,id desc limit ?",
                (rs, row) -> new Event(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant(), rs.getString(6)),
                merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }
    public record DeadLetter(UUID id, UUID eventId, String topic, int partition, long offset, Instant receivedAt) { }
    @Transactional(readOnly = true)
    public Cursor.Page<DeadLetter> deadLetters(UUID merchant, int limit, String cursor) {
        access.require(merchant); var key = Cursor.parse(cursor);
        var rows = jdbc.query("select id,event_id,source_topic,source_partition,source_offset,received_at from dead_letter where merchant_id=? and (received_at,id)<(?,?) order by received_at desc,id desc limit ?",
                (rs, row) -> new DeadLetter(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4), rs.getLong(5), rs.getTimestamp(6).toInstant()),
                merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.receivedAt(), row.id()));
    }
}
