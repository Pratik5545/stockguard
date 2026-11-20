package com.example.stockguard.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    // Caller holds the user row lock for the whole business transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> IdempotentResult<T> execute(UUID userId, String scope, String key, String canonicalRequest,
                                         int status, Class<T> type, Supplier<T> action) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1-128 letters, digits, dots, underscores, colons or hyphens.");
        }
        String hash = hash(canonicalRequest);
        var records = jdbc.query("""
                SELECT request_hash, response_status, response_body FROM idempotency_records
                WHERE user_id = ? AND operation_scope = ? AND idempotency_key = ?
                """, (row, index) -> new Stored(row.getString(1), row.getInt(2), row.getString(3)), userId, scope, key);
        if (!records.isEmpty()) {
            var stored = records.getFirst();
            if (!stored.hash().equals(hash)) throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Idempotency key was already used with a different request.");
            return new IdempotentResult<>(json.readValue(stored.body(), type), stored.status(), true);
        }
        T body = action.get();
        jdbc.update("""
                INSERT INTO idempotency_records
                (user_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, scope, key, hash, status, json.writeValueAsString(body), Timestamp.from(clock.instant()));
        return new IdempotentResult<>(body, status, false);
    }

    private record Stored(String hash, int status, String body) {}
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }
}
