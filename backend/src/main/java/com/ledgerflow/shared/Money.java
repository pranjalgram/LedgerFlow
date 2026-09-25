package com.ledgerflow.shared;

import java.util.Objects;

public record Money(long amountMinor, Currency currency) {
    public static final long MAX_COMMAND_AMOUNT = 9_000_000_000_000L;

    public enum Currency {
        INR(2);
        private final int exponent;
        Currency(int exponent) { this.exponent = exponent; }
        public int exponent() { return exponent; }
    }

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor <= 0 || amountMinor > MAX_COMMAND_AMOUNT) {
            throw new DomainException(400, "invalid-amount", "Amount must be positive minor units within the supported limit.");
        }
    }
}
