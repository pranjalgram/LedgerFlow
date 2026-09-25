package com.ledgerflow.wallet;

import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.shared.Cursor;
import com.ledgerflow.shared.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WalletQueries {
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    private final WalletService wallets;
    public WalletQueries(JdbcTemplate jdbc, MerchantAccess access, WalletService wallets) { this.jdbc = jdbc; this.access = access; this.wallets = wallets; }

    public record WalletRow(UUID id, String label, String kind, String currency, String balanceMinor, Instant createdAt) { }
    public record Balance(UUID walletId, String currency, String balanceMinor, long version, Instant asOf) { }
    public record History(UUID id, UUID ledgerTransactionId, String businessType, String side, long amount, String currency, Instant postedAt) { }
    public record Transfer(UUID id, UUID sourceWalletId, UUID destinationWalletId, long amount, String currency, UUID ledgerTransactionId, Instant createdAt) { }

    public Cursor.Page<WalletRow> list(UUID merchant, int limit, String cursor) {
        access.require(merchant); Cursor key = Cursor.parse(cursor);
        var rows = jdbc.query("""
                select w.id,w.label,w.kind,w.currency,a.balance_minor,w.created_at from wallet w
                join ledger_account a on a.id=w.account_id and a.merchant_id=w.merchant_id
                where w.merchant_id=? and (w.created_at,w.id)<(?,?) order by w.created_at desc,w.id desc limit ?
                """, (rs, row) -> new WalletRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }

    public Balance balance(UUID merchant, UUID id) {
        access.require(merchant);
        var rows = jdbc.query("""
                select w.id,w.currency,a.balance_minor,a.version,clock_timestamp() from wallet w
                join ledger_account a on a.id=w.account_id and a.merchant_id=w.merchant_id where w.merchant_id=? and w.id=?
                """, (rs, row) -> new Balance(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getTimestamp(5).toInstant()), merchant, id);
        if (rows.isEmpty()) throw new DomainException(404, "wallet-not-found", "The wallet was not found.");
        return rows.getFirst();
    }

    public Cursor.Page<History> history(UUID merchant, UUID wallet, int limit, String cursor) {
        access.require(merchant); var account = wallets.require(merchant, wallet); Cursor key = Cursor.parse(cursor);
        var rows = jdbc.query("""
                select e.id,t.id,t.business_type,e.side,e.amount_minor,e.currency,t.posted_at
                from ledger_entry e join ledger_transaction t on t.id=e.transaction_id and t.merchant_id=e.merchant_id
                where e.merchant_id=? and e.account_id=? and (t.posted_at,e.id)<(?,?) order by t.posted_at desc,e.id desc limit ?
                """, (rs, row) -> new History(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant()), merchant, account.accountId(), Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.postedAt(), row.id()));
    }

    public Cursor.Page<Transfer> transfers(UUID merchant, int limit, String cursor) {
        access.require(merchant); Cursor key = Cursor.parse(cursor);
        var rows = jdbc.query("""
                select id,source_wallet_id,destination_wallet_id,amount_minor,currency,ledger_transaction_id,created_at
                from transfer where merchant_id=? and (created_at,id)<(?,?) order by created_at desc,id desc limit ?
                """, (rs, row) -> new Transfer(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getLong(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getTimestamp(7).toInstant()), merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }

    public Transfer transfer(UUID merchant, UUID id) {
        access.require(merchant);
        var rows = jdbc.query("select id,source_wallet_id,destination_wallet_id,amount_minor,currency,ledger_transaction_id,created_at from transfer where merchant_id=? and id=?",
                (rs, row) -> new Transfer(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getLong(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getTimestamp(7).toInstant()), merchant, id);
        if (rows.isEmpty()) throw new DomainException(404, "transfer-not-found", "The transfer was not found.");
        return rows.getFirst();
    }
}
