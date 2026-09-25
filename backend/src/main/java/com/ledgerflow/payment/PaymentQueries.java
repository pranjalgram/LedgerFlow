package com.ledgerflow.payment;

import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.payment.internal.PaymentRows;
import com.ledgerflow.shared.Cursor;
import com.ledgerflow.shared.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class PaymentQueries {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MerchantAccess access;
    public PaymentQueries(JdbcTemplate jdbc, ObjectMapper json, MerchantAccess access) { this.jdbc = jdbc; this.json = json; this.access = access; }
    public record Transition(String fromState, String toState, long version, Instant occurredAt, String reasonCode) { }

    public Payment detail(UUID merchant, UUID id) {
        access.require(merchant);
        var rows = jdbc.query("select " + PaymentRows.COLUMNS + " from payment where merchant_id=? and id=?", (rs, row) -> PaymentRows.map(rs, json), merchant, id);
        if (rows.isEmpty()) throw new DomainException(404, "payment-not-found", "The payment was not found.");
        return rows.getFirst();
    }

    public Cursor.Page<Payment> list(UUID merchant, int limit, String cursor, PaymentState status, String reference) {
        access.require(merchant); Cursor key = Cursor.parse(cursor);
        var rows = jdbc.query("select " + PaymentRows.COLUMNS + """
                 from payment where merchant_id=? and (created_at,id)<(?,?)
                 and (?::varchar is null or status=?) and (?::varchar is null or reference=?)
                 order by created_at desc,id desc limit ?
                """, (rs, row) -> PaymentRows.map(rs, json), merchant, Timestamp.from(key.time()), key.id(),
                status == null ? null : status.name(), status == null ? null : status.name(), reference, reference, Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }

    public List<Transition> timeline(UUID merchant, UUID id) {
        detail(merchant, id);
        return jdbc.query("select from_state,to_state,aggregate_version,occurred_at,reason_code from payment_transition where merchant_id=? and payment_id=? order by aggregate_version",
                (rs, row) -> new Transition(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant(), rs.getString(5)), merchant, id);
    }
}
