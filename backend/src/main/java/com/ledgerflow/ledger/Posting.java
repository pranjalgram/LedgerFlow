package com.ledgerflow.ledger;

import com.ledgerflow.shared.DomainException;
import com.ledgerflow.shared.Money;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Posting(UUID merchantId, String businessType, UUID businessId, Money.Currency currency,
                      List<Entry> entries, UUID reversesTransactionId) {
    public enum Side { DEBIT, CREDIT }
    public record Entry(UUID accountId, Side side, long amountMinor) {
        public Entry {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(side, "side");
            if (amountMinor <= 0 || amountMinor > Money.MAX_COMMAND_AMOUNT) throw invalid();
        }
    }

    public Posting {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(currency, "currency");
        if (businessType == null || !businessType.matches("[A-Z_]{1,40}")) throw invalid();
        entries = List.copyOf(entries);
        if (entries.size() < 2 || entries.size() > 100 || entries.stream().map(Entry::accountId).distinct().count() < 2) throw invalid();
        BigInteger net = entries.stream().map(entry -> BigInteger.valueOf(entry.amountMinor())
                .multiply(entry.side() == Side.DEBIT ? BigInteger.ONE : BigInteger.ONE.negate()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (net.signum() != 0) throw invalid();
    }

    private static DomainException invalid() {
        return new DomainException(400, "invalid-posting", "A journal requires balanced positive entries across distinct accounts.");
    }
}
