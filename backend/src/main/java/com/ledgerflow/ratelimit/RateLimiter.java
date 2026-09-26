package com.ledgerflow.ratelimit;

import com.ledgerflow.shared.SecretDigest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    private static final DefaultRedisScript<String> SCRIPT = new DefaultRedisScript<>("""
            local n = tonumber(redis.call('GET', KEYS[1]) or '0')
            if n <= tonumber(ARGV[1]) then n = redis.call('INCR', KEYS[1]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); ttl = tonumber(ARGV[2]) end
            return tostring(n) .. ':' .. tostring(ttl)
            """, String.class);
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final int windowMs;
    private final AtomicLong retryAt = new AtomicLong();
    public RateLimiter(StringRedisTemplate redis, MeterRegistry meters,
            @Value("${ledgerflow.rate-limit.enabled:true}") boolean enabled,
            @Value("${ledgerflow.rate-limit.window-ms:60000}") int windowMs) {
        if (windowMs < 100 || windowMs > 3600000) throw new IllegalArgumentException("Rate window must be 100..3600000 ms");
        this.redis = redis; this.meters = meters; this.enabled = enabled; this.windowMs = windowMs;
    }
    public Decision check(String dimension, String identity, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Rate limit must be positive");
        if (!enabled) return new Decision(200, limit, limit, 0);
        if (System.nanoTime() < retryAt.get()) return unavailable(limit);
        try {
            String result = redis.execute(SCRIPT, List.of("lf:rate:v1:" + dimension + ":" + SecretDigest.sha256(identity)),
                    Integer.toString(limit), Integer.toString(windowMs));
            if (result == null) return unavailable(limit);
            String[] values = result.split(":", 2);
            long count = Long.parseLong(values[0]); long ttl = Long.parseLong(values[1]);
            boolean allowed = count <= limit;
            if (!allowed) meters.counter("ledgerflow.rate_limit.rejected", "dimension", dimension).increment();
            return new Decision(allowed ? 200 : 429, limit, Math.max(0, limit - count), Math.max(1, (ttl + 999) / 1000));
        } catch (DataAccessException failure) {
            retryAt.set(System.nanoTime() + 1_000_000_000L);
            return unavailable(limit);
        }
    }
    private Decision unavailable(int limit) {
        meters.counter("ledgerflow.rate_limit.unavailable").increment();
        return new Decision(503, limit, 0, 1);
    }
    public record Decision(int status, int limit, long remaining, long retryAfterSeconds) { }
}
