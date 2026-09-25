package com.ledgerflow.ledger;

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
@Transactional(readOnly = true)
public class LedgerQueries {
    private final JdbcTemplate jdbc;
    public LedgerQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Account(UUID id, String currency, String kind, String purpose, String balanceMinor, long version) { }
    public record Journal(UUID id, String currency, String businessType, UUID businessId, Instant postedAt) { }
    public record Entry(UUID id, UUID accountId, String side, long amountMinor, int ordinal) { }
    public record Detail(Journal transaction, List<Entry> entries) { }

    public List<Account> accounts(UUID merchantId) {
        return jdbc.query("select id,currency,kind,purpose,balance_minor,version from ledger_account where merchant_id=? order by id",
                (rs, row) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6)), merchantId);
    }

    public Cursor.Page<Journal> transactions(UUID merchantId, int limit, String cursor) {
        Cursor key = Cursor.parse(cursor);
        var rows = jdbc.query("""
                select id,currency,business_type,business_id,posted_at from ledger_transaction
                where merchant_id=? and (posted_at,id)<(?,?) order by posted_at desc,id desc limit ?
                """, (rs, row) -> new Journal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant()), merchantId,
                Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.postedAt(), row.id()));
    }

    public Detail detail(UUID merchantId, UUID id) {
        var journals = jdbc.query("select id,currency,business_type,business_id,posted_at from ledger_transaction where merchant_id=? and id=?",
                (rs, row) -> new Journal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant()), merchantId, id);
        if (journals.isEmpty()) throw new DomainException(404, "journal-not-found", "The ledger transaction was not found.");
        var entries = jdbc.query("select id,account_id,side,amount_minor,ordinal from ledger_entry where merchant_id=? and transaction_id=? order by ordinal",
                (rs, row) -> new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4), rs.getInt(5)), merchantId, id);
        return new Detail(journals.getFirst(), entries);
    }
}
