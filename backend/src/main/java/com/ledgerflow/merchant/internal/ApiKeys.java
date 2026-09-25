package com.ledgerflow.merchant.internal;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.shared.ApiPrincipal;
import com.ledgerflow.shared.DomainException;
import com.ledgerflow.shared.SecretDigest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("apiKeyAuthenticationManager")
class ApiKeys implements AuthenticationManager {
    private static final Pattern FORMAT = Pattern.compile("^(lf_test_[a-f0-9]{16})_[A-Za-z0-9_-]{43}$");
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    ApiKeys(JdbcTemplate jdbc, MerchantAccess access, AuditLog audit) { this.jdbc = jdbc; this.access = access; this.audit = audit; }

    record Key(UUID id, String name, String prefix, boolean canWrite, Instant createdAt, Instant lastUsedAt, Instant revokedAt) { }
    record Created(UUID id, String prefix, String secret) { }

    @Transactional
    public Created create(UUID merchant, String name, boolean canWrite) {
        var tenant = access.require(merchant); tenant.requireWrite();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String prefix = "lf_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String secret = prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into api_key(id,merchant_id,name,prefix,secret_hash,can_write) values (?,?,?,?,?,?)",
                id, merchant, name.strip(), prefix, SecretDigest.sha256(secret), canWrite);
        audit.append(merchant, tenant.actorId(), "api-key.created", id);
        return new Created(id, prefix, secret);
    }

    @Transactional(readOnly = true)
    public List<Key> list(UUID merchant) {
        access.require(merchant).requireWrite();
        return jdbc.query("select id,name,prefix,can_write,created_at,last_used_at,revoked_at from api_key where merchant_id=? order by created_at desc,id desc limit 100",
                (rs, row) -> new Key(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()), merchant);
    }

    @Transactional
    public void revoke(UUID merchant, UUID id) {
        var tenant = access.require(merchant); tenant.requireWrite();
        int count = jdbc.update("update api_key set revoked_at=coalesce(revoked_at,now()) where merchant_id=? and id=?", merchant, id);
        if (count == 0) throw new DomainException(404, "key-not-found", "The API key was not found.");
        audit.append(merchant, tenant.actorId(), "api-key.revoked", id);
    }

    @Override
    @Transactional
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) throw new BadCredentialsException("Invalid API key");
        String secret = bearer.getToken(); var match = FORMAT.matcher(secret);
        if (!match.matches()) throw new BadCredentialsException("Invalid API key");
        var rows = jdbc.query("""
                select k.id,k.merchant_id,k.can_write,k.secret_hash from api_key k join merchant m on m.id=k.merchant_id
                where k.prefix=? and k.revoked_at is null and m.status='ACTIVE'
                """, (rs, row) -> new Credential(new ApiPrincipal(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)), rs.getString(4)), match.group(1));
        String expected = rows.isEmpty() ? "0".repeat(64) : rows.getFirst().hash();
        boolean valid = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), SecretDigest.sha256(secret).getBytes(StandardCharsets.US_ASCII));
        if (!valid || rows.isEmpty()) throw new BadCredentialsException("Invalid API key");
        var principal = rows.getFirst().principal();
        jdbc.update("update api_key set last_used_at=now() where id=?", principal.keyId());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority("API_KEY")));
    }

    private record Credential(ApiPrincipal principal, String hash) { }
}
