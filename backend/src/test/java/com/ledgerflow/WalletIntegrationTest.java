package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class WalletIntegrationTest extends IntegrationSupport {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void oneHundredConcurrentRequestsCannotOverspendAndAllAcceptedJournalsBalance() throws Exception {
        var user = register(); UUID source = wallet(user); UUID destination = wallet(user);
        fund(user, source, 1_000_000);
        var results = concurrentTransfers(user, source, destination, 100, index -> UUID.randomUUID().toString());
        assertThat(results.stream().filter(response -> response.statusCode() == 201).count()).isEqualTo(10);
        assertThat(results.stream().filter(response -> response.statusCode() == 409).count()).isEqualTo(90);
        assertThat(balance(user, source)).isEqualTo("0");
        assertThat(balance(user, destination)).isEqualTo("1000000");
        assertThat(jdbc.queryForObject("select count(*) from transfer where merchant_id=?", Long.class, user.merchantId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("""
                select count(*) from (select transaction_id from ledger_entry where merchant_id=? group by transaction_id
                having sum(case side when 'DEBIT' then amount_minor else -amount_minor end)<>0) bad
                """, Long.class, user.merchantId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where merchant_id=? and event_type='transfer.completed'", Long.class, user.merchantId())).isEqualTo(10);
    }

    @Test
    void concurrentIdenticalRequestsReturnOneStoredResponseAndOneTransfer() throws Exception {
        var user = register(); UUID source = wallet(user); UUID destination = wallet(user); fund(user, source, 1_000_000);
        String key = UUID.randomUUID().toString();
        var results = concurrentTransfers(user, source, destination, 20, index -> key);
        assertThat(results).allMatch(response -> response.statusCode() == 201);
        assertThat(results.stream().map(this::body).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(response -> response.headers().firstValue("Idempotency-Replayed").orElse("").equals("false")).count()).isEqualTo(1);
        assertThat(balance(user, source)).isEqualTo("900000");
        assertThat(jdbc.queryForObject("select count(*) from transfer where merchant_id=?", Long.class, user.merchantId())).isEqualTo(1);
        var mismatch = transfer(user, source, destination, 1, key);
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(body(mismatch).get("code").asString()).isEqualTo("idempotency-mismatch");
        assertThat(transfer(user, source, destination, 100_000, key).statusCode()).isEqualTo(201);
    }

    @Test
    void crossTenantWalletIdsAndFractionalAmountsAreRejected() throws Exception {
        var first = register(); var second = register(); UUID source = wallet(first); UUID other = wallet(second);
        fund(first, source, 1000);
        assertThat(transfer(first, source, other, 100, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/wallets/" + source + "/balance", null, second.token(), second.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/api/v1/wallets/" + source + "/funding", Map.of("amount", new java.math.BigDecimal("1.5"), "currency", "INR"),
                first.token(), first.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
        assertThat(balance(first, source)).isEqualTo("1000");
    }

    @Test
    void expiredResponseTombstoneNeverReexecutesTheCommand() throws Exception {
        var user = register(); UUID source = wallet(user); UUID destination = wallet(user); fund(user, source, 1000);
        String key = UUID.randomUUID().toString();
        assertThat(transfer(user, source, destination, 100, key).statusCode()).isEqualTo(201);
        try (var owner = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
                var statement = owner.prepareStatement("update idempotency_record set response_expires_at=now()-interval '1 day' where merchant_id=? and key=?")) {
            statement.setObject(1, user.merchantId()); statement.setString(2, key); statement.executeUpdate();
        }
        var response = transfer(user, source, destination, 100, key);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).get("code").asString()).isEqualTo("idempotency-response-expired");
        assertThat(balance(user, source)).isEqualTo("900");
    }

    private UUID wallet(Account user) throws Exception {
        var response = request("POST", "/api/v1/wallets", Map.of("label", "Concurrency wallet", "kind", "CUSTOMER", "currency", "INR"), user.token(), user.merchantId());
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).get("id").asString());
    }
    private void fund(Account user, UUID wallet, long amount) throws Exception {
        var response = request("POST", "/api/v1/wallets/" + wallet + "/funding", Map.of("amount", amount, "currency", "INR"), user.token(), user.merchantId(), UUID.randomUUID().toString());
        assertThat(response.statusCode()).isEqualTo(201);
    }
    private String balance(Account user, UUID wallet) throws Exception {
        return body(request("GET", "/api/v1/wallets/" + wallet + "/balance", null, user.token(), user.merchantId())).get("balanceMinor").asString();
    }
    private HttpResponse<String> transfer(Account user, UUID source, UUID destination, long amount, String key) throws Exception {
        return request("POST", "/api/v1/transfers", Map.of("sourceWalletId", source, "destinationWalletId", destination,
                "amount", amount, "currency", "INR"), user.token(), user.merchantId(), key);
    }

    private List<HttpResponse<String>> concurrentTransfers(Account user, UUID source, UUID destination, int count, IntFunction<String> keys) throws Exception {
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrency barrier timed out");
                    return transfer(user, source, destination, 100_000, keys.apply(index));
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); } finally { start.countDown(); }
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(45, TimeUnit.SECONDS));
            return responses;
        }
    }
}
