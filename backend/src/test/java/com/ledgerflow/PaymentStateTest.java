package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ledgerflow.payment.PaymentState;
import com.ledgerflow.shared.DomainException;
import org.junit.jupiter.api.Test;

class PaymentStateTest {
    @Test
    void captureAndCancellationFollowExplicitTransitions() {
        assertThatCode(() -> PaymentState.CREATED.requireTransition(PaymentState.PROCESSING)).doesNotThrowAnyException();
        assertThatCode(() -> PaymentState.CREATED.requireTransition(PaymentState.CANCELLED)).doesNotThrowAnyException();
        assertThatCode(() -> PaymentState.PROCESSING.requireTransition(PaymentState.SUCCEEDED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PaymentState.CREATED.requireTransition(PaymentState.SUCCEEDED)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PaymentState.SUCCEEDED.requireTransition(PaymentState.PROCESSING)).isInstanceOf(DomainException.class);
        for (PaymentState terminal : new PaymentState[]{PaymentState.FAILED, PaymentState.CANCELLED, PaymentState.REFUNDED}) {
            for (PaymentState target : PaymentState.values()) {
                assertThatThrownBy(() -> terminal.requireTransition(target)).isInstanceOf(DomainException.class);
            }
        }
    }
}
