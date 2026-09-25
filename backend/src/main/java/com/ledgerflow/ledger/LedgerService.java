package com.ledgerflow.ledger;

import com.ledgerflow.shared.Money;
import com.ledgerflow.outbox.Outbox;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Outbox outbox;
    private final com.ledgerflow.shared.Telemetry telemetry;

    public LedgerService(JdbcTemplate jdbc, ObjectMapper json, Outbox outbox, com.ledgerflow.shared.Telemetry telemetry) { this.jdbc = jdbc; this.json = json; this.outbox = outbox; this.telemetry = telemetry; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> cashControl(UUID merchantId, Money.Currency currency) {
        return jdbc.query("select id from ledger_account where merchant_id=? and currency=? and purpose='CASH_CONTROL'",
                (rs, row) -> rs.getObject(1, UUID.class), merchantId, currency.name()).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createAccount(UUID merchantId, Money.Currency currency, boolean cashControl) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("select create_ledger_account(?,?,?,?,?)", UUID.class,
                id, merchantId, currency.name(), cashControl ? "ASSET" : "LIABILITY", cashControl ? "CASH_CONTROL" : "WALLET");
    }

    /** Empty is a normal insufficient-funds outcome; no journal or projection change has occurred. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> post(Posting posting) {
        return telemetry.observe("ledgerflow.ledger.post", null, () -> postJournal(posting));
    }
    private Optional<UUID> postJournal(Posting posting) {
        UUID id = UUID.randomUUID();
        String entries = json.writeValueAsString(posting.entries().stream().map(entry -> Map.of(
                "account_id", entry.accountId(), "side", entry.side().name(), "amount", entry.amountMinor())).toList());
        UUID posted = jdbc.queryForObject("select post_journal(?,?,?,?,?,?::jsonb,?)", UUID.class,
                id, posting.merchantId(), posting.currency().name(), posting.businessType(), posting.businessId(), entries,
                posting.reversesTransactionId());
        if (posted != null) outbox.append(posting.merchantId(), "ledger.transaction.posted", "ledger", posted, 1,
                Map.of("ledgerTransactionId", posted, "currency", posting.currency().name(), "businessType", posting.businessType(), "businessId", posting.businessId()));
        return Optional.ofNullable(posted);
    }
}
