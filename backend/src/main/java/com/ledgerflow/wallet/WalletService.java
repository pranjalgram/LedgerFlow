package com.ledgerflow.wallet;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.ledger.LedgerService;
import com.ledgerflow.ledger.Posting;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.outbox.Outbox;
import com.ledgerflow.shared.DomainException;
import com.ledgerflow.shared.Idempotency;
import com.ledgerflow.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {
    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final MerchantAccess access;
    private final Idempotency idempotency;
    private final Outbox outbox;
    private final AuditLog audit;

    public WalletService(JdbcTemplate jdbc, LedgerService ledger, MerchantAccess access, Idempotency idempotency, Outbox outbox, AuditLog audit) {
        this.jdbc = jdbc; this.ledger = ledger; this.access = access; this.idempotency = idempotency; this.outbox = outbox; this.audit = audit;
    }

    public enum Kind { CUSTOMER, SETTLEMENT }
    public record Wallet(UUID id, UUID accountId, String label, Kind kind, Money.Currency currency, String customerId, Instant createdAt) { }
    public record TransferRequest(UUID sourceWalletId, UUID destinationWalletId, long amount, Money.Currency currency) { }
    public record Movement(UUID id, UUID sourceWalletId, UUID destinationWalletId, long amount, Money.Currency currency, UUID ledgerTransactionId) { }
    public record Funding(UUID id, UUID walletId, long amount, Money.Currency currency, UUID ledgerTransactionId) { }

    @Transactional
    public Wallet create(UUID merchant, String label, Kind kind, Money.Currency currency, String customerId) {
        var tenant = access.require(merchant); tenant.requireWrite();
        jdbc.queryForObject("select id from merchant where id=? for update", UUID.class, merchant);
        if (kind == Kind.SETTLEMENT && customerId != null) throw new DomainException(400, "invalid-wallet", "Settlement wallets cannot have customer IDs.");
        if (ledger.cashControl(merchant, currency).isEmpty()) ledger.createAccount(merchant, currency, true);
        var account = ledger.createAccount(merchant, currency, false);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into wallet(id,merchant_id,account_id,currency,label,kind,customer_id) values (?,?,?,?,?,?,?)",
                id, merchant, account, currency.name(), label.strip(), kind.name(), customerId);
        audit.append(merchant, tenant.actorId(), "wallet.created", id);
        return require(merchant, id);
    }

    @Transactional
    public Idempotency.Response fund(UUID merchant, UUID walletId, Money money, String key) {
        var tenant = access.require(merchant); tenant.requireWrite();
        return idempotency.execute(merchant, "v1:fund:" + walletId, key, money, () -> {
            var wallet = require(merchant, walletId); sameCurrency(wallet, money.currency());
            UUID id = UUID.randomUUID();
            UUID control = ledger.cashControl(merchant, money.currency()).orElseThrow();
            UUID journal = ledger.post(posting(merchant, "FUNDING", id, control, wallet.accountId(), money)).orElseThrow();
            jdbc.update("insert into funding(id,merchant_id,wallet_id,currency,amount_minor,ledger_transaction_id) values (?,?,?,?,?,?)",
                    id, merchant, walletId, money.currency().name(), money.amountMinor(), journal);
            audit.append(merchant, tenant.actorId(), "wallet.funded", walletId);
            outbox.append(merchant, "wallet.funded", "funding", id, 1,
                    Map.of("fundingId", id, "walletId", walletId, "amountMinor", money.amountMinor(), "currency", money.currency().name()));
            return idempotency.response(201, new Funding(id, walletId, money.amountMinor(), money.currency(), journal), "/api/v1/ledger/transactions/" + journal);
        });
    }

    @Transactional
    public Idempotency.Response transfer(UUID merchant, TransferRequest request, String key) {
        var tenant = access.require(merchant); tenant.requireWrite();
        var money = new Money(request.amount(), request.currency());
        if (request.sourceWalletId().equals(request.destinationWalletId())) throw new DomainException(400, "same-wallet", "Transfer wallets must differ.");
        return idempotency.execute(merchant, "v1:transfer", key, request, () -> {
            var source = require(merchant, request.sourceWalletId()); var destination = require(merchant, request.destinationWalletId());
            sameCurrency(source, money.currency()); sameCurrency(destination, money.currency());
            UUID id = UUID.randomUUID();
            var journal = ledger.post(posting(merchant, "TRANSFER", id, source.accountId(), destination.accountId(), money));
            if (journal.isEmpty()) return idempotency.insufficientFunds();
            jdbc.update("insert into transfer(id,merchant_id,source_wallet_id,destination_wallet_id,currency,amount_minor,ledger_transaction_id) values (?,?,?,?,?,?,?)",
                    id, merchant, source.id(), destination.id(), money.currency().name(), money.amountMinor(), journal.get());
            audit.append(merchant, tenant.actorId(), "transfer.completed", id);
            outbox.append(merchant, "transfer.completed", "transfer", id, 1,
                    Map.of("transferId", id, "amountMinor", money.amountMinor(), "currency", money.currency().name(), "ledgerTransactionId", journal.get()));
            return idempotency.response(201, new Movement(id, source.id(), destination.id(), money.amountMinor(), money.currency(), journal.get()), "/api/v1/transfers/" + id);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Wallet require(UUID merchant, UUID walletId) {
        var rows = jdbc.query("select id,account_id,label,kind,currency,customer_id,created_at from wallet where merchant_id=? and id=?",
                (rs, row) -> new Wallet(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        Kind.valueOf(rs.getString(4)), Money.Currency.valueOf(rs.getString(5)), rs.getString(6), rs.getTimestamp(7).toInstant()), merchant, walletId);
        if (rows.isEmpty()) throw new DomainException(404, "wallet-not-found", "The wallet was not found.");
        return rows.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Wallet settlement(UUID merchant, Money.Currency currency) {
        var ids = jdbc.query("select id from wallet where merchant_id=? and currency=? and kind='SETTLEMENT'",
                (rs, row) -> rs.getObject(1, UUID.class), merchant, currency.name());
        if (ids.isEmpty()) throw new DomainException(409, "settlement-required", "Create a settlement wallet for this currency first.");
        return require(merchant, ids.getFirst());
    }

    public static Posting posting(UUID merchant, String kind, UUID id, UUID debit, UUID credit, Money money) {
        return new Posting(merchant, kind, id, money.currency(), List.of(new Posting.Entry(debit, Posting.Side.DEBIT, money.amountMinor()),
                new Posting.Entry(credit, Posting.Side.CREDIT, money.amountMinor())), null);
    }

    public static void sameCurrency(Wallet wallet, Money.Currency currency) {
        if (wallet.currency() != currency) throw new DomainException(400, "currency-mismatch", "Wallet currency does not match the request.");
    }
}
