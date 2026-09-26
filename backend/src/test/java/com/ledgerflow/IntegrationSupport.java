package com.ledgerflow;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@ActiveProfiles("demo")
@org.springframework.test.context.TestPropertySource(properties = "ledgerflow.rate-limit.enabled=false")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationSupport {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6");
    private static final Path KEYS = keys();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    static {
        DATABASE.start();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create role ledgerflow_runtime nologin");
            statement.execute("create role ledgerflow_ledger_owner nologin");
            statement.execute("create role ledgerflow_app login password '" + APP_PASSWORD + "'");
            statement.execute("grant ledgerflow_runtime to ledgerflow_app");
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "ledgerflow_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        // Several cached test application contexts share one database; budget pools collectively.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 10);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 10000);
        registry.add("ledgerflow.reconciliation.enabled", () -> false);
        registry.add("ledgerflow.auth.private-key", () -> KEYS.resolve("private.pem").toUri().toString());
        registry.add("ledgerflow.auth.public-key", () -> KEYS.resolve("public.pem").toUri().toString());
    }

    @Value("${local.server.port}")
    private int port;
    @Autowired
    protected ObjectMapper json;

    protected HttpResponse<String> request(String method, String path, Object body, String token, UUID merchant)
            throws IOException, InterruptedException {
        return request(method, path, body, token, merchant, null);
    }

    protected HttpResponse<String> request(String method, String path, Object body, String token, UUID merchant, String key)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (merchant != null) request.header("X-Merchant-Id", merchant.toString());
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    protected JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    protected Account register() throws IOException, InterruptedException {
        String email = UUID.randomUUID() + "@example.test";
        String password = "test-password-" + UUID.randomUUID();
        var response = request("POST", "/api/v1/auth/register",
                Map.of("email", email, "password", password, "merchantName", "Integration merchant"), null, null);
        if (response.statusCode() != 201) throw new AssertionError("Registration failed: " + response.statusCode());
        var resource = body(response);
        var login = body(request("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null, null));
        return new Account(UUID.fromString(resource.get("userId").asString()), UUID.fromString(resource.get("merchantId").asString()),
                email, login.get("accessToken").asString(), login.get("refreshToken").asString());
    }

    protected record Account(UUID userId, UUID merchantId, String email, String token, String refresh) { }

    private static Path keys() {
        try {
            var directory = Files.createTempDirectory("ledgerflow-test-keys-");
            directory.toFile().deleteOnExit();
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var pair = generator.generateKeyPair();
            writeKey(directory.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
            writeKey(directory.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
            return directory;
        } catch (IOException | GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void writeKey(Path path, String label, byte[] value) throws IOException {
        Files.writeString(path, "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(value)
                + "\n-----END " + label + "-----\n");
        path.toFile().deleteOnExit();
    }
}
