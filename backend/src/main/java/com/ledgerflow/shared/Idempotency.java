package com.ledgerflow.shared;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class Idempotency {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonMapper canonical = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public Idempotency(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record Response(int status, JsonNode body, String location, boolean replayed) {
        public ResponseEntity<JsonNode> http() {
            var response = ResponseEntity.status(status).header("Idempotency-Replayed", Boolean.toString(replayed));
            response.contentType(status >= 400 ? org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON : org.springframework.http.MediaType.APPLICATION_JSON);
            if (location != null) response.location(URI.create(location));
            return response.body(body);
        }
    }

    public Response response(int status, Object body, String location) {
        return new Response(status, json.valueToTree(body), location, false);
    }

    public Response insufficientFunds() {
        return response(409, java.util.Map.of("type", "urn:ledgerflow:problem:insufficient-funds", "title", "Insufficient funds",
                "status", 409, "code", "insufficient-funds", "detail", "The source wallet has insufficient funds."), null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Response execute(UUID merchantId, String operation, String key, Object request, Supplier<Response> command) {
        if (key == null || !key.matches("[!-~]{8,128}")) {
            throw new DomainException(400, "invalid-idempotency-key", "Idempotency-Key must contain 8 to 128 printable ASCII characters.");
        }
        String fingerprint = fingerprint(request);
        jdbc.execute("set local lock_timeout='3s'");
        jdbc.execute("set local statement_timeout='10s'");
        int inserted = jdbc.update("""
                insert into idempotency_record(merchant_id,operation,key,fingerprint) values (?,?,?,?)
                on conflict do nothing
                """, merchantId, operation, key, fingerprint);
        if (inserted == 0) {
            return jdbc.queryForObject("""
                    select fingerprint,status_code,response_json,location,response_expires_at>now() as fresh
                    from idempotency_record where merchant_id=? and operation=? and key=?
                    """, (rs, row) -> {
                        if (!fingerprint.equals(rs.getString("fingerprint"))) throw new DomainException(409, "idempotency-mismatch", "The key was already used with a different request.");
                        if (!rs.getBoolean("fresh") || rs.getString("response_json") == null) throw new DomainException(409, "idempotency-response-expired", "The key remains reserved; retrieve the original resource.");
                        return new Response(rs.getInt("status_code"), json.readTree(rs.getString("response_json")), rs.getString("location"), true);
                    }, merchantId, operation, key);
        }
        Response response = command.get();
        jdbc.update("update idempotency_record set status_code=?,response_json=?::jsonb,location=? where merchant_id=? and operation=? and key=?",
                response.status(), json.writeValueAsString(response.body()), response.location(), merchantId, operation, key);
        return response;
    }

    public String fingerprint(Object request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
