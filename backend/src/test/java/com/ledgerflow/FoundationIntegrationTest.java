package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTest extends IntegrationSupport {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void cleanDatabaseMigratesAndReadinessReportsUp() throws Exception {
        assertThat(jdbc.queryForObject("select exponent from currency where code = 'INR'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Long.class))
                .isZero();
        var response = get("/actuator/health/readiness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("password", "jdbc:");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void unimplementedApisAndDetailedManagementEndpointsAreNotPublic() throws Exception {
        assertThat(get("/api/v1/wallets").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
