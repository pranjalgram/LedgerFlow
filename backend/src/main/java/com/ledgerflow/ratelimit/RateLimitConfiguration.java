package com.ledgerflow.ratelimit;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class RateLimitConfiguration {
    @Bean
    FilterRegistrationBean<RateLimitFilter> preAuthenticationRateLimit(RateLimiter limiter, ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new RateLimitFilter(limiter, json, false));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
