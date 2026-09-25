package com.ledgerflow.identity;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.identity.internal.UserEntity;
import com.ledgerflow.identity.internal.UserRepository;
import com.ledgerflow.shared.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final String issuer;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public IdentityService(UserRepository users, PasswordEncoder passwords, JwtEncoder tokens,
            JdbcTemplate jdbc, AuditLog audit, @Value("${ledgerflow.auth.issuer}") String issuer) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.audit = audit;
        this.issuer = issuer;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record UserView(UUID id, String email) { }
    public record AuthTokens(String accessToken, String refreshToken, long expiresIn, String tokenType) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserView create(String email, String password) {
        var user = users.saveAndFlush(new UserEntity(UUID.randomUUID(), normalize(email), passwords.encode(password)));
        return new UserView(user.id(), user.email());
    }

    @Transactional
    public Optional<AuthTokens> login(String email, String password) {
        var user = users.findByEmail(normalize(email));
        boolean matches = passwords.matches(password, user.map(UserEntity::passwordHash).orElse(dummyHash));
        if (!matches || user.isEmpty() || !user.get().enabled()) return Optional.empty();
        if (passwords.upgradeEncoding(user.get().passwordHash())) user.get().updatePasswordHash(passwords.encode(password));
        audit.append(null, user.get().id(), "identity.login", user.get().id());
        return Optional.of(issue(user.get().id(), UUID.randomUUID(), Instant.now().plusSeconds(604800)));
    }

    @Transactional
    public Optional<AuthTokens> refresh(String refreshToken) {
        // Lock the entire family through its stable first session row, before any child row.
        var rows = jdbc.query("select family_id from refresh_session where token_hash=?",
                (rs, row) -> rs.getObject(1, UUID.class), hash(refreshToken));
        if (rows.isEmpty()) return Optional.empty();
        UUID family = rows.getFirst();
        jdbc.queryForObject("select id from refresh_session where id=? for update", UUID.class, family);
        var sessions = jdbc.query("""
                select user_id,expires_at,consumed_at,revoked_at from refresh_session where token_hash=?
                """, (rs, row) -> new Session(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) != null, rs.getTimestamp(4) != null), hash(refreshToken));
        var session = sessions.getFirst();
        if (session.consumed() || session.revoked() || !session.expiresAt().isAfter(Instant.now())) {
            revokeFamily(family);
            audit.append(null, session.userId(), "identity.refresh-rejected", family);
            return Optional.empty();
        }
        if (users.findById(session.userId()).filter(UserEntity::enabled).isEmpty()) {
            revokeFamily(family);
            return Optional.empty();
        }
        jdbc.update("update refresh_session set consumed_at=now() where token_hash=?", hash(refreshToken));
        return Optional.of(issue(session.userId(), family, session.expiresAt()));
    }

    @Transactional
    public void logout(String refreshToken) {
        var families = jdbc.query("select family_id from refresh_session where token_hash=?",
                (rs, row) -> rs.getObject(1, UUID.class), hash(refreshToken));
        if (!families.isEmpty()) {
            UUID family = families.getFirst();
            jdbc.queryForObject("select id from refresh_session where id=? for update", UUID.class, family);
            revokeFamily(family);
            UUID userId = jdbc.queryForObject("select user_id from refresh_session where id=?", UUID.class, family);
            audit.append(null, userId, "identity.logout", family);
        }
    }

    @Transactional(readOnly = true)
    public UserView currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) throw unauthorized();
        UUID userId;
        UUID family;
        try {
            userId = UUID.fromString(jwt.getSubject());
            family = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw unauthorized();
        }
        Boolean active = jdbc.queryForObject("""
                select exists(select 1 from refresh_session where id=? and user_id=?
                and revoked_at is null and expires_at>now())
                """, Boolean.class, family, userId);
        if (!Boolean.TRUE.equals(active)) throw unauthorized();
        return users.findById(userId).filter(UserEntity::enabled)
                .map(user -> new UserView(user.id(), user.email())).orElseThrow(IdentityService::unauthorized);
    }

    @Transactional(readOnly = true)
    public UUID findUser(String email) {
        return users.findByEmail(normalize(email)).map(UserEntity::id)
                .orElseThrow(() -> new DomainException(404, "user-not-found", "The registered user was not found."));
    }

    private AuthTokens issue(UUID userId, UUID family, Instant expiresAt) {
        var now = Instant.now();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        boolean existing = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from refresh_session where id=?)", Boolean.class, family));
        jdbc.update("""
                insert into refresh_session(id,user_id,token_hash,family_id,expires_at) values (?,?,?,?,?)
                """, existing ? UUID.randomUUID() : family, userId, hash(refresh), family, Timestamp.from(expiresAt));
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
                .audience(List.of("ledgerflow-api")).issuedAt(now).expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString()).claim("sid", family.toString()).build();
        String access = tokens.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("ledgerflow-v1").build(), claims)).getTokenValue();
        return new AuthTokens(access, refresh, 600, "Bearer");
    }

    private void revokeFamily(UUID family) {
        jdbc.update("update refresh_session set revoked_at=now() where family_id=? and revoked_at is null", family);
    }

    private record Session(UUID userId, Instant expiresAt, boolean consumed, boolean revoked) { }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static DomainException unauthorized() {
        return new DomainException(401, "invalid-credentials", "Valid credentials are required.");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", impossible);
        }
    }
}
