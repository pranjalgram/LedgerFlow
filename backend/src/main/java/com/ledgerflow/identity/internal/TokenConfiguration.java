package com.ledgerflow.identity.internal;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
class TokenConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    RSAKey signingKey(@Value("${ledgerflow.auth.private-key}") Resource privateKey,
            @Value("${ledgerflow.auth.public-key}") Resource publicKey) throws IOException {
        try (var privateStream = privateKey.getInputStream(); var publicStream = publicKey.getInputStream()) {
            var key = RsaKeyConverters.pkcs8().convert(privateStream);
            var publicRsa = RsaKeyConverters.x509().convert(publicStream);
            if (key == null || publicRsa == null || publicRsa.getModulus().bitLength() < 3072
                    || !key.getModulus().equals(publicRsa.getModulus())) {
                throw new IllegalArgumentException("Matching RSA keys of at least 3072 bits are required");
            }
            return new RSAKey.Builder(publicRsa).privateKey(key).keyID("ledgerflow-v1").build();
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey key, @Value("${ledgerflow.auth.issuer}") String issuer)
            throws com.nimbusds.jose.JOSEException {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                token -> token.getAudience().contains("ledgerflow-api")
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }
}
