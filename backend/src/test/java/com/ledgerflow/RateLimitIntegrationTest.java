package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import com.ledgerflow.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RateLimitIntegrationTest extends IntegrationSupport {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);
    static { REDIS.start(); }
    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("ledgerflow.rate-limit.enabled", () -> true);
    }
    @Autowired StringRedisTemplate redis;
    @Autowired RateLimiter limiter;
    @Autowired MeterRegistry meters;
    @BeforeEach void clearTestCounters() {
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
    }
    @Test
    void concurrentCountersAreAtomicAndExpire() throws Exception {
        String identity = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(IntStream.range(0, 100).mapToObj(index -> (Callable<Integer>) () -> limiter.check("test", identity, 10).status()).toList());
            int accepted = 0;
            for (var result : results) if (result.get() == 200) accepted++;
            assertThat(accepted).isEqualTo(10);
        }
        var shortWindow = new RateLimiter(redis, meters, true, 1000);
        String expiring = UUID.randomUUID().toString();
        assertThat(shortWindow.check("test", expiring, 1).status()).isEqualTo(200);
        assertThat(shortWindow.check("test", expiring, 1).status()).isEqualTo(429);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(shortWindow.check("test", expiring, 1).status()).isEqualTo(200));
    }
    @Test
    void authenticationAbuseReturnsProblemDetailsAndRetryHeaders() throws Exception {
        for (int index = 0; index < 30; index++) assertThat(request("POST", "/api/v1/auth/login", Map.of(), null, null).statusCode()).isEqualTo(400);
        var response = request("POST", "/api/v1/auth/login", Map.of(), null, null);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(body(response).get("code").asString()).isEqualTo("rate-limit-exceeded");
        assertThat(request("GET", "/actuator/health/readiness", null, null, null).statusCode()).isEqualTo(200);
    }
    @Test
    void authenticatedLimitsAreScopedToVerifiedIdentity() throws Exception {
        var first = register(); var second = register();
        for (int index = 0; index < 300; index++) limiter.check("principal", "user:" + first.userId(), 300);
        assertThat(request("GET", "/api/v1/merchants", null, first.token(), null).statusCode()).isEqualTo(429);
        assertThat(request("GET", "/api/v1/merchants", null, second.token(), null).statusCode()).isEqualTo(200);
    }
    @Test
    void redisOutageFailsClosedAndRecoversWithoutAffectingReadiness() throws Exception {
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            var response = request("POST", "/api/v1/auth/login", Map.of(), null, null);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(body(response).get("code").asString()).isEqualTo("rate-limiter-unavailable");
            assertThat(request("GET", "/actuator/health/readiness", null, null, null).statusCode()).isEqualTo(200);
        } finally { docker.unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(request("POST", "/api/v1/auth/login", Map.of(), null, null).statusCode()).isEqualTo(400));
    }
}
