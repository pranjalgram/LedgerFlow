package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.shared.SecretDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentIntegrationTest extends IntegrationSupport {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void merchantKeyCreatesCapturesAndReplaysPaymentWithoutTenantHeader() throws Exception {
        var owner = register(); var wallets = wallets(owner, 100_000);
        var keyResponse = request("POST", "/api/v1/api-keys", Map.of("name", "Checkout", "canWrite", true), owner.token(), owner.merchantId());
        assertThat(keyResponse.statusCode()).isEqualTo(201);
        String key = body(keyResponse).get("secret").asString(); UUID keyId = UUID.fromString(body(keyResponse).get("id").asString());
        String idempotency = UUID.randomUUID().toString();
        var command = Map.of("amount", 49_900, "currency", "INR", "customerId", "customer_123", "reference", "ORDER-98372", "metadata", Map.of("cartId", "cart_92"));
        var created = request("POST", "/api/v1/payments", command, key, null, idempotency);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = body(created).get("id").asString();
        var replay = request("POST", "/api/v1/payments", command, key, null, idempotency);
        assertThat(body(replay)).isEqualTo(body(created));
        String captureKey = UUID.randomUUID().toString();
        var captured = request("POST", "/api/v1/payments/" + id + "/confirm", null, key, null, captureKey);
        assertThat(captured.statusCode()).isEqualTo(200);
        assertThat(body(captured).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(body(captured).get("ledgerTransactionId").isNull()).isFalse();
        assertThat(body(request("POST", "/api/v1/payments/" + id + "/confirm", null, key, null, captureKey))).isEqualTo(body(captured));
        assertThat(request("POST", "/api/v1/payments/" + id + "/confirm", null, key, null, UUID.randomUUID().toString()).statusCode()).isEqualTo(409);
        assertThat(body(request("GET", "/api/v1/wallets/" + wallets[0] + "/balance", null, key, null)).get("balanceMinor").asString()).isEqualTo("50100");
        assertThat(body(request("GET", "/api/v1/payments/" + id + "/timeline", null, key, null)).size()).isEqualTo(3);
        var listed = request("GET", "/api/v1/api-keys", null, owner.token(), owner.merchantId());
        assertThat(listed.body()).doesNotContain(key, "secret_hash", "secretHash");
        assertThat(jdbc.queryForObject("select secret_hash from api_key where id=?", String.class, keyId)).isEqualTo(SecretDigest.sha256(key));
        assertThat(request("GET", "/api/v1/api-keys", null, key, owner.merchantId()).statusCode()).isEqualTo(403);
        var outsider = register();
        assertThat(request("GET", "/api/v1/payments/" + id, null, outsider.token(), outsider.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/payments", null, key, outsider.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/v1/api-keys/" + keyId, null, owner.token(), owner.merchantId()).statusCode()).isEqualTo(204);
        assertThat(request("GET", "/api/v1/payments", null, key, null).statusCode()).isEqualTo(401);
    }

    @Test
    void insufficientFundsPersistFailureAndCancellationNeverPosts() throws Exception {
        var user = register(); var wallets = wallets(user, 100);
        String id = create(user, wallets[0], 101);
        var failed = request("POST", "/api/v1/payments/" + id + "/confirm", null, user.token(), user.merchantId(), UUID.randomUUID().toString());
        assertThat(failed.statusCode()).isEqualTo(200);
        assertThat(body(failed).get("status").asString()).isEqualTo("FAILED");
        assertThat(body(failed).get("failureCode").asString()).isEqualTo("insufficient-funds");
        String cancelledId = create(user, wallets[0], 100);
        var cancelled = request("POST", "/api/v1/payments/" + cancelledId + "/cancel", null, user.token(), user.merchantId(), UUID.randomUUID().toString());
        assertThat(body(cancelled).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(request("POST", "/api/v1/payments/" + cancelledId + "/confirm", null, user.token(), user.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where merchant_id=? and business_type='PAYMENT'", Long.class, user.merchantId())).isZero();
    }

    @Test
    void readOnlyApiKeyCannotCreatePaymentAndConcurrentConfirmationPostsOnce() throws Exception {
        var user = register(); var wallets = wallets(user, 1000);
        var readKey = body(request("POST", "/api/v1/api-keys", Map.of("name", "Reports", "canWrite", false), user.token(), user.merchantId())).get("secret").asString();
        assertThat(request("POST", "/api/v1/payments", Map.of("amount", 100, "currency", "INR", "customerWalletId", wallets[0], "reference", "read-denied"),
                readKey, null, UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
        String id = create(user, wallets[0], 100);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> confirm = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
                return request("POST", "/api/v1/payments/" + id + "/confirm", null, user.token(), user.merchantId(), UUID.randomUUID().toString()).statusCode();
            };
            var first = executor.submit(confirm); var second = executor.submit(confirm); start.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where merchant_id=? and business_type='PAYMENT'", Long.class, user.merchantId())).isEqualTo(1);
    }

    private UUID[] wallets(Account user, long funding) throws Exception {
        var customer = request("POST", "/api/v1/wallets", Map.of("label", "Customer", "kind", "CUSTOMER", "currency", "INR", "customerId", "customer_123"), user.token(), user.merchantId());
        var settlement = request("POST", "/api/v1/wallets", Map.of("label", "Settlement", "kind", "SETTLEMENT", "currency", "INR"), user.token(), user.merchantId());
        UUID a = UUID.fromString(body(customer).get("id").asString()); UUID b = UUID.fromString(body(settlement).get("id").asString());
        assertThat(request("POST", "/api/v1/wallets/" + a + "/funding", Map.of("amount", funding, "currency", "INR"), user.token(), user.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        return new UUID[]{a, b};
    }
    private String create(Account user, UUID customer, long amount) throws Exception {
        var created = request("POST", "/api/v1/payments", Map.of("amount", amount, "currency", "INR", "customerWalletId", customer, "reference", UUID.randomUUID().toString()),
                user.token(), user.merchantId(), UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        return body(created).get("id").asString();
    }
}
