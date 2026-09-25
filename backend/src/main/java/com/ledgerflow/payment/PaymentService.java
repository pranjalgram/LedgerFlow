package com.ledgerflow.payment;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.ledger.LedgerService;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.outbox.Outbox;
import com.ledgerflow.payment.internal.PaymentRows;
import com.ledgerflow.shared.DomainException;
import com.ledgerflow.shared.Idempotency;
import com.ledgerflow.shared.Money;
import com.ledgerflow.wallet.WalletService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MerchantAccess access;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final Idempotency idempotency;
    private final AuditLog audit;
    private final Outbox outbox;

    public PaymentService(JdbcTemplate jdbc, ObjectMapper json, MerchantAccess access, WalletService wallets, LedgerService ledger,
            Idempotency idempotency, AuditLog audit, Outbox outbox) {
        this.jdbc = jdbc; this.json = json; this.access = access; this.wallets = wallets; this.ledger = ledger;
        this.idempotency = idempotency; this.audit = audit; this.outbox = outbox;
    }

    public record Create(long amount, @NotNull Money.Currency currency, UUID customerWalletId,
                         @Size(min = 1, max = 120) String customerId, @NotBlank @Size(max = 120) String reference,
                         @Size(max = 20) Map<@NotBlank @Size(max = 64) String, @NotNull @Size(max = 256) String> metadata) {
        public Create { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }

    @Transactional
    public Idempotency.Response create(UUID merchant, Create request, String key) {
        var tenant = access.require(merchant); tenant.requireWrite();
        var money = new Money(request.amount(), request.currency());
        if ((request.customerId() == null) == (request.customerWalletId() == null)) throw new DomainException(400, "customer-required", "Supply exactly one customerWalletId or customerId.");
        return idempotency.execute(merchant, "v1:payment:create", key, request, () -> {
            var customer = request.customerWalletId() == null ? wallets.customer(merchant, request.currency(), request.customerId()) : wallets.require(merchant, request.customerWalletId());
            WalletService.sameCurrency(customer, request.currency());
            if (customer.kind() != WalletService.Kind.CUSTOMER) throw new DomainException(400, "invalid-customer-wallet", "Payments require a customer wallet.");
            var settlement = wallets.settlement(merchant, request.currency());
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into payment(id,merchant_id,customer_wallet_id,settlement_wallet_id,currency,amount_minor,reference,metadata,status)
                    values (?,?,?,?,?,?,?,?::jsonb,'CREATED')
                    """, id, merchant, customer.id(), settlement.id(), money.currency().name(), money.amountMinor(), request.reference(), json.writeValueAsString(request.metadata()));
            var payment = load(merchant, id, false);
            recordTransition(merchant, tenant.actorId(), null, payment);
            return idempotency.response(201, payment, "/api/v1/payments/" + id);
        });
    }

    @Transactional
    public Idempotency.Response confirm(UUID merchant, UUID id, String key) {
        var tenant = access.require(merchant); tenant.requireWrite();
        return idempotency.execute(merchant, "v1:payment:confirm:" + id, key, Map.of(), () -> {
            var original = load(merchant, id, true);
            var processing = transition(merchant, tenant.actorId(), original, PaymentState.PROCESSING, null, null, 0);
            var customer = wallets.require(merchant, processing.customerWalletId());
            var settlement = wallets.require(merchant, processing.settlementWalletId());
            var journal = ledger.post(WalletService.posting(merchant, "PAYMENT", id, customer.accountId(), settlement.accountId(), new Money(processing.amount(), processing.currency())));
            var terminal = journal.isPresent()
                    ? transition(merchant, tenant.actorId(), processing, PaymentState.SUCCEEDED, journal.get(), null, 0)
                    : transition(merchant, tenant.actorId(), processing, PaymentState.FAILED, null, "insufficient-funds", 0);
            return idempotency.response(200, terminal, "/api/v1/payments/" + id);
        });
    }

    @Transactional
    public Idempotency.Response cancel(UUID merchant, UUID id, String key) {
        var tenant = access.require(merchant); tenant.requireWrite();
        return idempotency.execute(merchant, "v1:payment:cancel:" + id, key, Map.of(), () -> {
            var cancelled = transition(merchant, tenant.actorId(), load(merchant, id, true), PaymentState.CANCELLED, null, null, 0);
            return idempotency.response(200, cancelled, "/api/v1/payments/" + id);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Payment lock(UUID merchant, UUID id) { return load(merchant, id, true); }

    private Payment load(UUID merchant, UUID id, boolean lock) {
        var rows = jdbc.query("select " + PaymentRows.COLUMNS + " from payment where merchant_id=? and id=?" + (lock ? " for update" : ""),
                (rs, row) -> PaymentRows.map(rs, json), merchant, id);
        if (rows.isEmpty()) throw new DomainException(404, "payment-not-found", "The payment was not found.");
        return rows.getFirst();
    }

    private Payment transition(UUID merchant, UUID actor, Payment current, PaymentState target, UUID journal, String failure, long refunded) {
        current.status().requireTransition(target);
        jdbc.update("update payment set status=?,ledger_transaction_id=?,failure_code=?,refunded_minor=?,version=version+1,updated_at=clock_timestamp() where merchant_id=? and id=?",
                target.name(), journal, failure, refunded, merchant, current.id());
        var next = load(merchant, current.id(), false);
        recordTransition(merchant, actor, current.status(), next);
        return next;
    }

    private void recordTransition(UUID merchant, UUID actor, PaymentState from, Payment payment) {
        jdbc.update("insert into payment_transition(id,merchant_id,payment_id,currency,from_state,to_state,aggregate_version,reason_code) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), merchant, payment.id(), payment.currency().name(), from == null ? null : from.name(), payment.status().name(), payment.version(), payment.failureCode());
        String type = "payment." + payment.status().name().toLowerCase(Locale.ROOT);
        audit.append(merchant, actor, type, payment.id());
        var data = new java.util.LinkedHashMap<String, Object>(Map.of("paymentId", payment.id(), "amountMinor", payment.amount(),
                "currency", payment.currency().name(), "status", payment.status().name(), "refundedMinor", payment.refundedAmount()));
        if (payment.ledgerTransactionId() != null) data.put("ledgerTransactionId", payment.ledgerTransactionId());
        if (payment.failureCode() != null) data.put("failureCode", payment.failureCode());
        outbox.append(merchant, type, "payment", payment.id(), payment.version(), data);
    }
}
