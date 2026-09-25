package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.ledgerflow.webhook.WebhookSecrets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookSecretsTest {
    @Test
    void encryptionIsRandomizedBoundToEndpointAndDetectsTampering() {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var secrets = new WebhookSecrets(Base64.getEncoder().encodeToString(key));
        UUID endpoint = UUID.randomUUID(); String secret = secrets.generate();
        String encrypted = secrets.encrypt(endpoint, secret);
        assertThat(secrets.decrypt(endpoint, encrypted)).isEqualTo(secret);
        assertThat(secrets.encrypt(endpoint, secret)).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> secrets.decrypt(UUID.randomUUID(), encrypted)).isInstanceOf(IllegalStateException.class);
        assertThat(WebhookSecrets.signature(secret, 123, "{}")).isNotEqualTo(WebhookSecrets.signature(secret, 124, "{}"));
    }
}
