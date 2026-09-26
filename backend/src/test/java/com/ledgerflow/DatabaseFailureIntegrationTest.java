package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.data-source-properties.socketTimeout=1")
class DatabaseFailureIntegrationTest extends IntegrationSupport {
    @Test
    void databaseOutageReturnsSafeRetryableFailureAndRecovers() throws Exception {
        var owner = register();
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(DATABASE.getContainerId()).exec();
        try {
            var response = request("GET", "/api/v1/wallets", null, owner.token(), owner.merchantId());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(body(response).get("code").asString()).isEqualTo("database-unavailable");
            assertThat(response.body()).doesNotContain("SQLException", "stackTrace", "password");
        } finally { docker.unpauseContainerCmd(DATABASE.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(request("GET", "/api/v1/wallets", null, owner.token(), owner.merchantId()).statusCode()).isEqualTo(200));
    }
}
