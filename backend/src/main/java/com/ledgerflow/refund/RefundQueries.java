package com.ledgerflow.refund;

import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.shared.Cursor;
import com.ledgerflow.shared.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RefundQueries {
    private static final String COLUMNS = "id,payment_id,amount_minor,currency,status,failure_code,ledger_transaction_id,created_at";
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    public RefundQueries(JdbcTemplate jdbc, MerchantAccess access) { this.jdbc = jdbc; this.access = access; }
    public record Refund(UUID id, UUID paymentId, long amount, String currency, String status, String failureCode, UUID ledgerTransactionId, Instant createdAt) { }

    public Refund detail(UUID merchant, UUID id) {
        access.require(merchant);
        var rows = jdbc.query("select " + COLUMNS + " from refund where merchant_id=? and id=?", (rs, row) -> map(rs), merchant, id);
        if (rows.isEmpty()) throw new DomainException(404, "refund-not-found", "The refund was not found.");
        return rows.getFirst();
    }

    public Cursor.Page<Refund> list(UUID merchant, int limit, String cursor, UUID paymentId) {
        access.require(merchant); Cursor key = Cursor.parse(cursor);
        var rows = jdbc.query("select " + COLUMNS + " from refund where merchant_id=? and (?::uuid is null or payment_id=?) and (created_at,id)<(?,?) order by created_at desc,id desc limit ?",
                (rs, row) -> map(rs), merchant, paymentId, paymentId, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }

    private Refund map(ResultSet rs) throws SQLException {
        return new Refund(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getObject(7, UUID.class), rs.getTimestamp(8).toInstant());
    }
}
