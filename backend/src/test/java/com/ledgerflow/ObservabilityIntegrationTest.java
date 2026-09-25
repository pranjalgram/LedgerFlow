package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.tracing.sampling.probability=1")
class ObservabilityIntegrationTest extends IntegrationSupport {
    private static final String PASSWORD = UUID.randomUUID().toString();
    @DynamicPropertySource
    static void metrics(DynamicPropertyRegistry registry) { registry.add("ledgerflow.metrics.password", () -> PASSWORD); }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Test
    void metricsRequireSeparateCredentialsAndExposeMeasuredHttpTraffic() throws Exception {
        assertThat(request("GET", "/actuator/prometheus", null, null, null).statusCode()).isEqualTo(401);
        String basic = Base64.getEncoder().encodeToString(("metrics:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                    .header("Authorization", "Basic " + basic).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("jvm_memory_used_bytes", "http_server_requests_seconds_count");
        }
    }
    @Test
    void httpTraceContextIsStoredAtomicallyWithFinancialEvent() throws Exception {
        var owner = register();
        var wallet = request("POST", "/api/v1/wallets", Map.of("label", "Traced wallet", "kind", "CUSTOMER", "currency", "INR"), owner.token(), owner.merchantId());
        assertThat(wallet.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(body(wallet).get("id").asString());
        assertThat(request("POST", "/api/v1/wallets/" + id + "/funding", Map.of("amount", 100, "currency", "INR"), owner.token(), owner.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        var contexts = jdbc.queryForList("select trace_context from outbox_event where merchant_id=?", String.class, owner.merchantId());
        assertThat(contexts).isNotEmpty().allSatisfy(parent -> assertThat(parent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
    }
}
