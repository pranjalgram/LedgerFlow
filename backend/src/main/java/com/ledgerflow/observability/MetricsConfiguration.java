package com.ledgerflow.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain metricsSecurity(HttpSecurity http, @Value("${ledgerflow.metrics.password:}") String password) throws Exception {
        var users = new InMemoryUserDetailsManager();
        var encoder = new BCryptPasswordEncoder();
        if (!password.isBlank()) users.createUser(User.withUsername("metrics").password(encoder.encode(password)).authorities("METRICS").build());
        var provider = new DaoAuthenticationProvider(users); provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/actuator/prometheus").csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(provider).authorizeHttpRequests(requests -> requests.anyRequest().hasAuthority("METRICS"))
                .httpBasic(Customizer.withDefaults()).build();
    }
}
