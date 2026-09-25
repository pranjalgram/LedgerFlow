package com.ledgerflow.refund;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.ledger.LedgerService;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.outbox.Outbox;
import com.ledgerflow.payment.PaymentService;
import com.ledgerflow.shared.DomainException;
import com.ledgerflow.shared.Idempotency;
import com.ledgerflow.shared.Money;
import com.ledgerflow.wallet.WalletService;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    private final PaymentService payments;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final Idempotency idempotency;
    private final AuditLog audit;
    private final Outbox outbox;
    private final RefundQueries queries;

    public RefundService(JdbcTemplate jdbc, MerchantAccess access, PaymentService payments, WalletService wallets, LedgerService ledger,
            Idempotency idempotency, AuditLog audit, Outbox outbox, RefundQueries queries) {
        this.jdbc = jdbc; this.access = access; this.payments = payments; this.wallets = wallets; this.ledger = ledger;
        this.idempotency = idempotency; this.audit = audit; this.outbox = outbox; this.queries = queries;
    }

    /** Null amount explicitly requests all remaining refundable minor units. */
    public record Create(Long amount, @NotNull Money.Currency currency) { }

    @Transactional
    public Idempotency.Response create(UUID merchant, UUID paymentId, Create request, String key) {
        var tenant = access.require(merchant); tenant.requireWrite();
        return idempotency.execute(merchant, "v1:refund:" + paymentId, key, request, () -> {
            var payment = payments.lock(merchant, paymentId);
            if (request.currency() != payment.currency()) throw new DomainException(400, "currency-mismatch", "Refund currency must match the payment.");
            long amount = request.amount() == null ? payment.refundBudget().remaining() : request.amount();
            payment.refundBudget().totalAfter(amount);
            var money = new Money(amount, request.currency());
            var customer = wallets.require(merchant, payment.customerWalletId());
            var settlement = wallets.require(merchant, payment.settlementWalletId());
            UUID id = UUID.randomUUID();
            var journal = ledger.post(WalletService.posting(merchant, "REFUND", id, settlement.accountId(), customer.accountId(), money));
            String status = journal.isPresent() ? "SUCCEEDED" : "FAILED";
            jdbc.update("insert into refund(id,merchant_id,payment_id,currency,amount_minor,status,failure_code,ledger_transaction_id) values (?,?,?,?,?,?,?,?)",
                    id, merchant, paymentId, money.currency().name(), amount, status, journal.isEmpty() ? "insufficient-settlement-funds" : null, journal.orElse(null));
            if (journal.isPresent()) payments.applyRefund(merchant, tenant.actorId(), paymentId, amount);
            String event = journal.isPresent() ? "refund.succeeded" : "refund.failed";
            audit.append(merchant, tenant.actorId(), event, id);
            var data = new java.util.LinkedHashMap<String, Object>(Map.of("refundId", id, "paymentId", paymentId, "amountMinor", amount,
                    "currency", money.currency().name(), "status", status));
            journal.ifPresent(value -> data.put("ledgerTransactionId", value));
            outbox.append(merchant, event, "refund", id, 1, data);
            return idempotency.response(201, queries.detail(merchant, id), "/api/v1/refunds/" + id);
        });
    }
}
