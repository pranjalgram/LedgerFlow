package com.ledgerflow.shared;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record Cursor(Instant time, UUID id) {
    public record Page<T>(List<T> items, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }

    public static Cursor parse(String token) {
        if (token == null) return new Cursor(Instant.parse("9999-12-31T23:59:59Z"), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        try {
            if (token.length() > 180) throw new IllegalArgumentException("cursor too long");
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException("invalid cursor");
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            throw new DomainException(400, "invalid-cursor", "The pagination cursor is invalid.");
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static int limit(int limit) {
        if (limit < 1 || limit > 100) throw new DomainException(400, "invalid-limit", "Limit must be between 1 and 100.");
        return limit;
    }

    public static <T> Page<T> page(List<T> rows, int limit, Function<T, Cursor> cursor) {
        boolean more = rows.size() > limit;
        List<T> items = more ? rows.subList(0, limit) : rows;
        return new Page<>(items, more ? cursor.apply(items.getLast()).encode() : null);
    }
}
