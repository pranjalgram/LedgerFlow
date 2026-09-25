package com.ledgerflow.payment;

import com.ledgerflow.shared.DomainException;

public record RefundBudget(long captured, long refunded, PaymentState state) {
    public RefundBudget {
        if (captured <= 0 || refunded < 0 || refunded > captured) throw new IllegalArgumentException("Invalid refund budget");
    }
    public long remaining() { return captured - refunded; }
    public long totalAfter(long requested) {
        if (state != PaymentState.SUCCEEDED && state != PaymentState.PARTIALLY_REFUNDED)
            throw new DomainException(409, "not-refundable", "Only successful payments with a remaining amount can be refunded.");
        if (requested <= 0) throw new DomainException(400, "invalid-amount", "Refund amount must be positive.");
        if (requested > remaining()) throw new DomainException(409, "refund-limit", "Refund exceeds the remaining payment amount.");
        return Math.addExact(refunded, requested);
    }
}
