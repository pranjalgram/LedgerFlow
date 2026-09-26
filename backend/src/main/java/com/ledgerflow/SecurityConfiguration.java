package com.ledgerflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import java.util.List;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
            @Qualifier("apiKeyAuthenticationManager") AuthenticationManager apiKeys,
            com.ledgerflow.ratelimit.RateLimiter limiter, tools.jackson.databind.ObjectMapper json) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("DASHBOARD")));
        var provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        var jwtManager = new ProviderManager(provider);
        var bearer = new DefaultBearerTokenResolver();
        return http
                // All credentials are explicit bearer/body tokens; no cookies or session authentication.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAfter(new com.ledgerflow.ratelimit.RateLimitFilter(limiter, json, true),
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/auth/**", "/api/v1/merchant", "/api/v1/merchant/**", "/api/v1/merchants",
                                "/api/v1/api-keys/**", "/api/v1/webhooks/**", "/api/v1/webhook-deliveries/**",
                                "/api/v1/reconciliation-runs/**", "/api/v1/operations/**", "/api/v1/audit-events").hasAuthority("DASHBOARD")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(request -> {
                    String token = bearer.resolve(request);
                    return token != null && token.startsWith("lf_test_") ? apiKeys : jwtManager;
                }))
                .build();
    }
}
