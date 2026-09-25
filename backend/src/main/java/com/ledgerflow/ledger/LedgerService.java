package com.ledgerflow.ledger;

import com.ledgerflow.shared.Money;
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

    public LedgerService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createAccount(UUID merchantId, Money.Currency currency, boolean cashControl) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("select create_ledger_account(?,?,?,?,?)", UUID.class,
                id, merchantId, currency.name(), cashControl ? "ASSET" : "LIABILITY", cashControl ? "CASH_CONTROL" : "WALLET");
    }

    /** Empty is a normal insufficient-funds outcome; no journal or projection change has occurred. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> post(Posting posting) {
        UUID id = UUID.randomUUID();
        String entries = json.writeValueAsString(posting.entries().stream().map(entry -> Map.of(
                "account_id", entry.accountId(), "side", entry.side().name(), "amount", entry.amountMinor())).toList());
        return Optional.ofNullable(jdbc.queryForObject("select post_journal(?,?,?,?,?,?::jsonb,?)", UUID.class,
                id, posting.merchantId(), posting.currency().name(), posting.businessType(), posting.businessId(), entries,
                posting.reversesTransactionId()));
    }
}
