package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RefundIntegrationTest extends IntegrationSupport {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void partialThenFullRefundRestoresFundsWithCompensatingHistoryAndReplay() throws Exception {
        var fixture = paid(); var user = fixture.user();
        String key = UUID.randomUUID().toString();
        var partial = refund(fixture, 250, key);
        assertThat(partial.statusCode()).isEqualTo(201);
        assertThat(body(partial).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(body(refund(fixture, 250, key))).isEqualTo(body(partial));
        var payment = body(request("GET", "/api/v1/payments/" + fixture.payment(), null, user.token(), user.merchantId()));
        assertThat(payment.get("status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(payment.get("refundedAmount").asLong()).isEqualTo(250);
        var remaining = request("POST", "/api/v1/payments/" + fixture.payment() + "/refunds", Map.of("currency", "INR"),
                user.token(), user.merchantId(), UUID.randomUUID().toString());
        assertThat(remaining.statusCode()).isEqualTo(201);
        assertThat(body(remaining).get("amount").asLong()).isEqualTo(750);
        assertThat(body(request("GET", "/api/v1/payments/" + fixture.payment(), null, user.token(), user.merchantId())).get("status").asString()).isEqualTo("REFUNDED");
        assertThat(body(request("GET", "/api/v1/wallets/" + fixture.customer() + "/balance", null, user.token(), user.merchantId())).get("balanceMinor").asString()).isEqualTo("1000");
        assertThat(refund(fixture, 1, UUID.randomUUID().toString()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where merchant_id=? and business_type='REFUND'", Long.class, user.merchantId())).isEqualTo(2);
        var outsider = register();
        assertThat(request("GET", "/api/v1/refunds/" + body(partial).get("id").asString(), null, outsider.token(), outsider.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/api/v1/payments/" + fixture.payment() + "/refunds", Map.of("amount", 1, "currency", "INR"),
                outsider.token(), outsider.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
    }

    @Test
    void concurrentDifferentRefundsCannotExceedOriginalPayment() throws Exception {
        var fixture = paid(); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<HttpResponse<String>> first = () -> { start.await(5, TimeUnit.SECONDS); return refund(fixture, 800, UUID.randomUUID().toString()); };
            Callable<HttpResponse<String>> second = () -> { start.await(5, TimeUnit.SECONDS); return refund(fixture, 700, UUID.randomUUID().toString()); };
            var a = executor.submit(first); var b = executor.submit(second); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS).statusCode(), b.get(15, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(201, 409);
        }
        Long total = jdbc.queryForObject("select sum(amount_minor) from refund where merchant_id=? and status='SUCCEEDED'", Long.class, fixture.user().merchantId());
        assertThat(total).isIn(700L, 800L);
        assertThat(jdbc.queryForObject("select refunded_minor from payment where id=?", Long.class, fixture.payment())).isEqualTo(total);
    }

    @Test
    void simultaneousDuplicateRefundsProduceOneCompensation() throws Exception {
        var fixture = paid(); String key = UUID.randomUUID().toString(); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<HttpResponse<String>> call = () -> { start.await(5, TimeUnit.SECONDS); return refund(fixture, 300, key); };
            var a = executor.submit(call); var b = executor.submit(call); start.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(201); assertThat(second.statusCode()).isEqualTo(201);
            assertThat(body(first)).isEqualTo(body(second));
        }
        assertThat(jdbc.queryForObject("select count(*) from refund where merchant_id=?", Long.class, fixture.user().merchantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select refunded_minor from payment where id=?", Long.class, fixture.payment())).isEqualTo(300);
    }

    @Test
    void insufficientSettlementLiquidityFailsWithoutReducingRefundableAmount() throws Exception {
        var fixture = paid(); var user = fixture.user();
        UUID other = wallet(user, "CUSTOMER");
        assertThat(request("POST", "/api/v1/transfers", Map.of("sourceWalletId", fixture.settlement(), "destinationWalletId", other, "amount", 600, "currency", "INR"),
                user.token(), user.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        String key = UUID.randomUUID().toString();
        var failed = refund(fixture, 800, key);
        assertThat(failed.statusCode()).isEqualTo(201);
        assertThat(body(failed).get("status").asString()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select refunded_minor from payment where id=?", Long.class, fixture.payment())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where merchant_id=? and business_type='REFUND'", Long.class, user.merchantId())).isZero();
        assertThat(request("POST", "/api/v1/wallets/" + fixture.settlement() + "/funding", Map.of("amount", 600, "currency", "INR"),
                user.token(), user.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        assertThat(body(refund(fixture, 800, key))).isEqualTo(body(failed));
        assertThat(body(refund(fixture, 800, UUID.randomUUID().toString())).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    private record Fixture(Account user, UUID customer, UUID settlement, UUID payment) { }
    private Fixture paid() throws Exception {
        var user = register(); UUID customer = wallet(user, "CUSTOMER"); UUID settlement = wallet(user, "SETTLEMENT");
        assertThat(request("POST", "/api/v1/wallets/" + customer + "/funding", Map.of("amount", 1000, "currency", "INR"),
                user.token(), user.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        var payment = request("POST", "/api/v1/payments", Map.of("amount", 1000, "currency", "INR", "customerWalletId", customer, "reference", "Refund test"),
                user.token(), user.merchantId(), UUID.randomUUID().toString());
        assertThat(payment.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(body(payment).get("id").asString());
        assertThat(body(request("POST", "/api/v1/payments/" + id + "/confirm", null, user.token(), user.merchantId(), UUID.randomUUID().toString())).get("status").asString()).isEqualTo("SUCCEEDED");
        return new Fixture(user, customer, settlement, id);
    }
    private UUID wallet(Account user, String kind) throws Exception {
        var response = request("POST", "/api/v1/wallets", Map.of("label", kind, "kind", kind, "currency", "INR"), user.token(), user.merchantId());
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).get("id").asString());
    }
    private HttpResponse<String> refund(Fixture fixture, long amount, String key) throws Exception {
        return request("POST", "/api/v1/payments/" + fixture.payment() + "/refunds", Map.of("amount", amount, "currency", "INR"),
                fixture.user().token(), fixture.user().merchantId(), key);
    }
}
