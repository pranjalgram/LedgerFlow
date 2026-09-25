package com.ledgerflow.webhook;

import com.ledgerflow.shared.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookSecrets {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public WebhookSecrets(@Value("${ledgerflow.webhooks.encryption-key:}") String encoded) {
        key = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
        if (key != null && key.length != 32) throw new IllegalArgumentException("Webhook encryption key must contain 32 bytes");
    }
    public String generate() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String encrypt(UUID endpoint, String secret) {
        requireKey();
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(endpoint.toString().getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Secret encryption failed", failure); }
    }
    public String decrypt(UUID endpoint, String encrypted) {
        requireKey();
        String[] parts = encrypted.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalStateException("Invalid encrypted secret envelope");
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD(endpoint.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Secret authentication failed", failure); }
    }
    public static String signature(String secret, long timestamp, String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("HMAC unavailable", failure); }
    }
    private void requireKey() {
        if (key == null) throw new DomainException(503, "webhook-key-unavailable", "Webhook encryption has not been configured.");
    }
}
