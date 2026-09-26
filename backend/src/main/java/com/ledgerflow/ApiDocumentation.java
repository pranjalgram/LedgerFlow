package com.ledgerflow;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ApiDocumentation {
    @Bean
    OpenAPI apiDescription() {
        return new OpenAPI().info(new Info().title("LedgerFlow simulation API").version("v1")
                .description("Integer minor units (INR paise). No real money. Financial commands require Idempotency-Key. Dashboard requests select a merchant with X-Merchant-Id; API keys have a fixed tenant. See docs/API.md for replay and lifecycle semantics."))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
    @Bean
    OpenApiCustomizer tenantAndPublicContracts() {
        return api -> api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
            if (List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").contains(path)) {
                operation.setSecurity(List.of());
            }
            if (operation.getParameters() != null) operation.getParameters().removeIf(parameter ->
                    "query".equals(parameter.getIn()) && List.of("merchant", "merchantId").contains(parameter.getName()));
            if (!path.startsWith("/api/v1/auth/") && !path.equals("/api/v1/merchants")
                    && (operation.getParameters() == null || operation.getParameters().stream().noneMatch(parameter -> "X-Merchant-Id".equals(parameter.getName())))) {
                operation.addParametersItem(new HeaderParameter().name("X-Merchant-Id").required(false)
                        .description("Required for dashboard JWTs; omitted for fixed-tenant API keys.").schema(new StringSchema().format("uuid")));
            }
            if (operation.getParameters() != null) operation.getParameters().stream()
                    .filter(parameter -> "Idempotency-Key".equals(parameter.getName())).forEach(parameter -> {
                        parameter.setRequired(true);
                        parameter.setDescription("Reuse for an unchanged retry. A changed request with the same tenant/operation/key returns 409.");
                    });
        }));
    }
}
