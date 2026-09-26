package com.ledgerflow.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public final class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    private final ObjectMapper json;
    private final boolean authenticated;
    public RateLimitFilter(RateLimiter limiter, ObjectMapper json, boolean authenticated) {
        this.limiter = limiter; this.json = json; this.authenticated = authenticated;
    }
    @Override protected String getAlreadyFilteredAttributeName() { return getClass().getName() + "." + authenticated; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String identity; String dimension; int limit;
        if (authenticated) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) { chain.doFilter(request, response); return; }
            identity = authentication.getPrincipal() instanceof com.ledgerflow.shared.ApiPrincipal api
                    ? "key:" + api.keyId() : "user:" + authentication.getName();
            dimension = "principal"; limit = 300;
        } else {
            identity = request.getRemoteAddr();
            boolean auth = request.getRequestURI().startsWith("/api/v1/auth/");
            dimension = auth ? "auth-ip" : "ip"; limit = auth ? 30 : 600;
        }
        var decision = limiter.check(dimension, identity, limit);
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        if (decision.status() == 200) { chain.doFilter(request, response); return; }
        response.setStatus(decision.status()); response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        boolean throttled = decision.status() == 429;
        String code = throttled ? "rate-limit-exceeded" : "rate-limiter-unavailable";
        json.writeValue(response.getOutputStream(), Map.of("type", "urn:ledgerflow:problem:" + code,
                "title", throttled ? "Too Many Requests" : "Service Unavailable", "status", decision.status(), "code", code,
                "detail", "Retry after the indicated delay using the same idempotency key for an unchanged command.",
                "correlationId", String.valueOf(request.getAttribute("requestId"))));
    }
}
