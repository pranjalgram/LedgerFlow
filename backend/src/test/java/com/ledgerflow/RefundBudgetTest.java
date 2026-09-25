package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.payment.PaymentState;
import com.ledgerflow.payment.RefundBudget;
import com.ledgerflow.shared.DomainException;
import org.junit.jupiter.api.Test;

class RefundBudgetTest {
    @Test
    void capsPartialRefundsAndRejectsInvalidStatesAndAmounts() {
        var budget = new RefundBudget(1000, 400, PaymentState.PARTIALLY_REFUNDED);
        assertThat(budget.remaining()).isEqualTo(600);
        assertThat(budget.totalAfter(600)).isEqualTo(1000);
        assertThatThrownBy(() -> budget.totalAfter(601)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> budget.totalAfter(0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new RefundBudget(1000, 0, PaymentState.FAILED).totalAfter(100)).isInstanceOf(DomainException.class);
        assertThat(new RefundBudget(Long.MAX_VALUE, Long.MAX_VALUE - 1, PaymentState.PARTIALLY_REFUNDED).totalAfter(1)).isEqualTo(Long.MAX_VALUE);
    }
}
