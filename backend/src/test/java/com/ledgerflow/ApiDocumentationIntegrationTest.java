package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"
})
class ApiDocumentationIntegrationTest extends IntegrationSupport {
    @Test
    void generatedContractContainsRealRoutesAndExplicitIdempotency() throws Exception {
        var response = request("GET", "/v3/api-docs", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var document = body(response);
        assertThat(document.get("paths").has("/api/v1/transfers")).isTrue();
        assertThat(document.get("paths").has("/api/v1/payments/{id}/refunds")).isTrue();
        var parameters = document.get("paths").get("/api/v1/transfers").get("post").get("parameters");
        boolean idempotencyRequired = false;
        for (var parameter : parameters) {
            assertThat(parameter.get("name").asString()).isNotEqualTo("merchant");
            if ("Idempotency-Key".equals(parameter.get("name").asString())) idempotencyRequired = parameter.get("required").asBoolean();
        }
        assertThat(idempotencyRequired).isTrue();
        assertThat(request("GET", "/swagger-ui/index.html", null, null, null).statusCode()).isEqualTo(200);
    }
}
