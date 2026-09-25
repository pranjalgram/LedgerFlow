package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.shared.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyTest {
    @Test
    void fingerprintUsesCanonicalMapOrderingAndIncludesEveryValue() {
        var idempotency = new Idempotency(null, JsonMapper.builder().build());
        var first = new LinkedHashMap<String, Object>(); first.put("amount", 100); first.put("metadata", Map.of("b", "2", "a", "1"));
        var second = new LinkedHashMap<String, Object>(); second.put("metadata", Map.of("a", "1", "b", "2")); second.put("amount", 100);
        assertThat(idempotency.fingerprint(first)).isEqualTo(idempotency.fingerprint(second));
        second.put("amount", 101);
        assertThat(idempotency.fingerprint(first)).isNotEqualTo(idempotency.fingerprint(second));
    }
}
