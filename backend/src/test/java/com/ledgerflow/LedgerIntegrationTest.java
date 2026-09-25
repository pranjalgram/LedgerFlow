package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.ledger.LedgerService;
import com.ledgerflow.ledger.Posting;
import com.ledgerflow.shared.Money;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerIntegrationTest extends IntegrationSupport {
    @Autowired private LedgerService ledger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void balancedPostingChangesProjectionAndInsufficientFundsChangesNothing() throws Exception {
        var merchant = register();
        var accounts = accounts(merchant.merchantId());
        var posted = tx().execute(status -> ledger.post(posting(merchant.merchantId(), accounts[0], accounts[1], 1000, null))).orElseThrow();
        assertThat(balance(accounts[0])).isEqualTo(1000);
        assertThat(balance(accounts[1])).isEqualTo(1000);
        var declined = tx().execute(status -> ledger.post(posting(merchant.merchantId(), accounts[1], accounts[0], 1001, null)));
        assertThat(declined).isEmpty();
        assertThat(balance(accounts[1])).isEqualTo(1000);
        assertThat(request("GET", "/api/v1/ledger/transactions/" + posted, null, merchant.token(), merchant.merchantId()).statusCode()).isEqualTo(200);
        var outsider = register();
        assertThat(request("GET", "/api/v1/ledger/transactions/" + posted, null, outsider.token(), outsider.merchantId()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select sum(case side when 'DEBIT' then amount_minor else -amount_minor end) from ledger_entry where transaction_id=?",
                Long.class, posted)).isZero();
    }

    @Test
    void rollbackIncludesProjectionAndHistoryAndRuntimeCannotBypassPosting() throws Exception {
        var merchant = register();
        var accounts = accounts(merchant.merchantId());
        tx().executeWithoutResult(status -> {
            ledger.post(posting(merchant.merchantId(), accounts[0], accounts[1], 500, null));
            status.setRollbackOnly();
        });
        assertThat(balance(accounts[1])).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where merchant_id=?", Long.class, merchant.merchantId())).isZero();
        assertThatThrownBy(() -> jdbc.update("update ledger_account set balance_minor=10 where id=?", accounts[1]))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entry")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("truncate ledger_transaction cascade")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsMalformedAndCrossTenantPostingEvenWithoutJavaValidation() throws Exception {
        var first = register(); var second = register();
        var a = accounts(first.merchantId()); var b = accounts(second.merchantId());
        String invalid = json.writeValueAsString(List.of(java.util.Map.of("account_id", a[0], "side", "DEBIT", "amount", 10),
                java.util.Map.of("account_id", a[1], "side", "CREDIT", "amount", 9)));
        assertThatThrownBy(() -> tx().execute(status -> jdbc.queryForObject("select post_journal(?,?,'INR','TEST',?,?::jsonb,null)", UUID.class,
                UUID.randomUUID(), first.merchantId(), UUID.randomUUID(), invalid))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx().execute(status -> ledger.post(posting(first.merchantId(), a[0], b[1], 10, null))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void deferredConstraintRejectsEmptyJournalAndCommittedJournalCannotBeExtended() throws Exception {
        var merchant = register(); var accounts = accounts(merchant.merchantId());
        UUID journal = tx().execute(status -> ledger.post(posting(merchant.merchantId(), accounts[0], accounts[1], 100, null))).orElseThrow();
        try (var owner = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            owner.setAutoCommit(false);
            try (var insert = owner.prepareStatement("insert into ledger_transaction(id,merchant_id,currency,business_type,business_id) values (?,?,'INR','EMPTY',?)")) {
                insert.setObject(1, UUID.randomUUID()); insert.setObject(2, merchant.merchantId()); insert.setObject(3, UUID.randomUUID());
                insert.executeUpdate();
                assertThatThrownBy(owner::commit).isInstanceOf(SQLException.class).hasMessageContaining("balanced");
                owner.rollback();
            }
            try (var insert = owner.prepareStatement("insert into ledger_entry(id,merchant_id,transaction_id,account_id,currency,side,amount_minor,ordinal) values (?,?,?,?,'INR','DEBIT',10,3)")) {
                insert.setObject(1, UUID.randomUUID()); insert.setObject(2, merchant.merchantId()); insert.setObject(3, journal); insert.setObject(4, accounts[0]);
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("committed journal");
                owner.rollback();
            }
        }
    }

    @Test
    void reversalInvertsOriginalAndBusinessReferenceCannotPostTwice() throws Exception {
        var merchant = register(); var accounts = accounts(merchant.merchantId());
        var command = posting(merchant.merchantId(), accounts[0], accounts[1], 100, null);
        UUID original = tx().execute(status -> ledger.post(command)).orElseThrow();
        assertThatThrownBy(() -> tx().execute(status -> ledger.post(command))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx().execute(status -> ledger.post(posting(merchant.merchantId(), accounts[1], accounts[0], 99, original))))
                .isInstanceOf(DataAccessException.class);
        tx().execute(status -> ledger.post(posting(merchant.merchantId(), accounts[1], accounts[0], 100, original)));
        assertThat(balance(accounts[0])).isZero();
        assertThat(balance(accounts[1])).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where merchant_id=?", Long.class, merchant.merchantId())).isEqualTo(2);
    }

    private UUID[] accounts(UUID merchant) {
        return tx().execute(status -> new UUID[]{ledger.createAccount(merchant, Money.Currency.INR, true),
                ledger.createAccount(merchant, Money.Currency.INR, false)});
    }
    private long balance(UUID account) { return jdbc.queryForObject("select balance_minor from ledger_account where id=?", Long.class, account); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private Posting posting(UUID merchant, UUID debit, UUID credit, long amount, UUID reversal) {
        return new Posting(merchant, "TEST", UUID.randomUUID(), Money.Currency.INR,
                List.of(new Posting.Entry(debit, Posting.Side.DEBIT, amount), new Posting.Entry(credit, Posting.Side.CREDIT, amount)), reversal);
    }
}
