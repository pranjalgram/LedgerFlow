package com.ledgerflow.payment;

import com.ledgerflow.shared.Money;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Payment(UUID id, UUID customerWalletId, UUID settlementWalletId, long amount, long refundedAmount,
                      Money.Currency currency, String reference, Map<String, String> metadata, PaymentState status,
                      String failureCode, UUID ledgerTransactionId, long version, Instant createdAt, Instant updatedAt) {
    public Payment { metadata = Map.copyOf(metadata); }
    public RefundBudget refundBudget() { return new RefundBudget(amount, refundedAmount, status); }
}
