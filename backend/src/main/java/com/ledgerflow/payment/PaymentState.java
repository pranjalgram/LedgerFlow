package com.ledgerflow.payment;

import com.ledgerflow.shared.DomainException;

public enum PaymentState {
    CREATED, PROCESSING, SUCCEEDED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED;

    public void requireTransition(PaymentState target) {
        boolean allowed = switch (this) {
            case CREATED -> target == PROCESSING || target == CANCELLED;
            case PROCESSING -> target == SUCCEEDED || target == FAILED;
            case SUCCEEDED, PARTIALLY_REFUNDED -> target == PARTIALLY_REFUNDED || target == REFUNDED;
            case FAILED, CANCELLED, REFUNDED -> false;
        };
        if (!allowed) throw new DomainException(409, "invalid-payment-state", "This payment cannot perform the requested transition.");
    }
}
