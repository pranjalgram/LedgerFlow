package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.outbox.Outbox;
import com.ledgerflow.webhook.WebhookDispatcher;
import com.ledgerflow.webhook.WebhookQueue;
import com.ledgerflow.webhook.WebhookSecrets;
import com.ledgerflow.webhook.WebhookTransport;
import com.ledgerflow.webhook.Webhooks;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class WebhookIntegrationTest extends IntegrationSupport {
    private static final HttpServer SERVER = server();
    private static final AtomicInteger STATUS = new AtomicInteger(500);
    private static final AtomicReference<String> RECEIVED = new AtomicReference<>();
    private static final AtomicReference<String> SIGNATURE = new AtomicReference<>();
    private static final AtomicReference<String> TIMESTAMP = new AtomicReference<>();
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
            server.createContext("/hook", exchange -> {
                RECEIVED.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                SIGNATURE.set(exchange.getRequestHeaders().getFirst("X-LedgerFlow-Signature"));
                TIMESTAMP.set(exchange.getRequestHeaders().getFirst("X-LedgerFlow-Timestamp"));
                exchange.sendResponseHeaders(STATUS.get(), -1); exchange.close();
            });
            server.createContext("/slow", exchange -> {
                try { Thread.sleep(6000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            server.start(); return server;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static String origin() { return "http://127.0.0.1:" + SERVER.getAddress().getPort(); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ledgerflow.webhooks.allowed-origins", WebhookIntegrationTest::origin);
        registry.add("ledgerflow.webhooks.allow-local", () -> true);
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        registry.add("ledgerflow.webhooks.encryption-key", () -> encoded);
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @Autowired Webhooks webhooks;
    @Autowired WebhookQueue queue;
    @Autowired WebhookTransport transport;
    @Autowired WebhookSecrets secrets;
    @Autowired JdbcTemplate jdbc;

    @Test
    void signedDeliveryRetriesOnceDeduplicatesAndSupportsAuditedReplay() throws Exception {
        STATUS.set(500);
        var owner = register();
        var created = request("POST", "/api/v1/webhooks", Map.of("url", origin() + "/hook", "subscriptions", List.of("payment.succeeded")), owner.token(), owner.merchantId());
        assertThat(created.statusCode()).isEqualTo(201);
        String secret = body(created).get("secret").asString();
        UUID endpoint = UUID.fromString(body(created).get("endpoint").get("id").asString());
        assertThat(jdbc.queryForObject("select encrypted_secret from webhook_endpoint where id=?", String.class, endpoint)).doesNotContain(secret);
        var event = new Outbox.Event(UUID.randomUUID(), 1, "payment.succeeded", Instant.now(), owner.merchantId(), "payment", UUID.randomUUID(), 1, 0, null, Map.of("amount", 100));
        webhooks.consume(event); webhooks.consume(event);
        assertThat(jdbc.queryForObject("select count(*) from webhook_delivery where event_id=?", Integer.class, event.id())).isEqualTo(1);
        UUID delivery = jdbc.queryForObject("select id from webhook_delivery where event_id=?", UUID.class, event.id());
        var dispatcher = new WebhookDispatcher(queue, transport, secrets);
        dispatcher.dispatchOnce();
        assertThat(state(delivery)).isEqualTo("PENDING");
        assertThat(SIGNATURE.get()).isEqualTo("v1=" + WebhookSecrets.signature(secret, Long.parseLong(TIMESTAMP.get()), RECEIVED.get()));
        assertThat(jdbc.queryForObject("select next_attempt_at>now() from webhook_delivery where id=?", Boolean.class, delivery)).isTrue();
        STATUS.set(204);
        jdbc.update("update webhook_delivery set next_attempt_at=now() where id=?", delivery);
        dispatcher.dispatchOnce();
        assertThat(state(delivery)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from webhook_attempt where delivery_id=?", Integer.class, delivery)).isEqualTo(2);
        assertThat(request("POST", "/api/v1/webhook-deliveries/" + delivery + "/replay", null, owner.token(), owner.merchantId()).statusCode()).isEqualTo(202);
        dispatcher.dispatchOnce();
        assertThat(jdbc.queryForObject("select attempts from webhook_delivery where id=?", Integer.class, delivery)).isEqualTo(3);
        var stranger = register();
        assertThat(request("GET", "/api/v1/webhook-deliveries/" + delivery + "/attempts", null, stranger.token(), stranger.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/v1/webhooks/" + endpoint, null, stranger.token(), stranger.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/webhooks", null, owner.token(), owner.merchantId()).body()).doesNotContain(secret);
    }

    @Test
    void expiredLeaseIsFencedAndExhaustedDeliveryRemainsFailed() throws Exception {
        var owner = register();
        assertThat(request("POST", "/api/v1/webhooks", Map.of("url", origin() + "/hook", "subscriptions", List.of("refund.succeeded")), owner.token(), owner.merchantId()).statusCode()).isEqualTo(201);
        var event = new Outbox.Event(UUID.randomUUID(), 1, "refund.succeeded", Instant.now(), owner.merchantId(), "refund", UUID.randomUUID(), 1, 0, null, Map.of());
        webhooks.consume(event);
        UUID id = jdbc.queryForObject("select id from webhook_delivery where event_id=?", UUID.class, event.id());
        var old = queue.claim().stream().filter(job -> job.id().equals(id)).findFirst().orElseThrow();
        jdbc.update("update webhook_delivery set lease_until=now()-interval '1 second',cycle_attempts=31 where id=?", id);
        var current = queue.claim().stream().filter(job -> job.id().equals(id)).findFirst().orElseThrow();
        assertThat(queue.finish(old, new WebhookTransport.Result(204, null, 1))).isFalse();
        assertThat(queue.finish(current, new WebhookTransport.Result(500, null, 1))).isTrue();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from webhook_attempt where delivery_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void timeoutIsBoundedAndUnapprovedDestinationsAreRejected() throws Exception {
        var owner = register();
        assertThat(request("POST", "/api/v1/webhooks", Map.of("url", "http://169.254.169.254/latest", "subscriptions", List.of("payment.succeeded")), owner.token(), owner.merchantId()).statusCode()).isEqualTo(400);
        var timeout = transport.post(origin() + "/slow", UUID.randomUUID(), "{}", "secret");
        assertThat(timeout.succeeded()).isFalse();
        assertThat(timeout.durationMs()).isLessThan(7500);
        try (var production = new WebhookTransport(origin(), false)) {
            assertThatThrownBy(() -> production.validate(origin() + "/hook")).isInstanceOf(com.ledgerflow.shared.DomainException.class);
        }
        try (var production = new WebhookTransport("https://127.0.0.1:443", false)) {
            assertThat(production.post("https://127.0.0.1/hook", UUID.randomUUID(), "{}", "secret").error()).isEqualTo("destination-blocked");
        }
    }
    private String state(UUID id) { return jdbc.queryForObject("select state from webhook_delivery where id=?", String.class, id); }
}
