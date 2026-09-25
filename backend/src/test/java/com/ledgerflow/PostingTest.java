package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.ledger.Posting;
import com.ledgerflow.shared.DomainException;
import com.ledgerflow.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingTest {
    @Test
    void rejectsUnbalancedNonpositiveAndSelfPostings() {
        UUID a = UUID.randomUUID(); UUID b = UUID.randomUUID();
        assertThatThrownBy(() -> posting(List.of(new Posting.Entry(a, Posting.Side.DEBIT, 100),
                new Posting.Entry(b, Posting.Side.CREDIT, 99)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Posting.Entry(a, Posting.Side.DEBIT, 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> posting(List.of(new Posting.Entry(a, Posting.Side.DEBIT, 100),
                new Posting.Entry(a, Posting.Side.CREDIT, 100)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, Money.Currency.INR)).isInstanceOf(DomainException.class);
    }

    @Test
    void copiesEntriesAndKeepsCurrencyExplicit() {
        var entries = new ArrayList<>(List.of(new Posting.Entry(UUID.randomUUID(), Posting.Side.DEBIT, 100),
                new Posting.Entry(UUID.randomUUID(), Posting.Side.CREDIT, 100)));
        var posting = posting(entries);
        entries.clear();
        assertThat(posting.entries()).hasSize(2);
        assertThat(posting.currency().exponent()).isEqualTo(2);
    }

    private Posting posting(List<Posting.Entry> entries) {
        return new Posting(UUID.randomUUID(), "TEST", UUID.randomUUID(), Money.Currency.INR, entries, null);
    }
}
