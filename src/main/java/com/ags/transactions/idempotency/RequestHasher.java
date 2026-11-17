package com.ags.transactions.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fingerprints a request so a reused Idempotency-Key with a different body can be detected.
 *
 * <p>It hashes the parsed, normalized request object, not the raw HTTP bytes, so whitespace,
 * field order or "5" vs "5.00" don't count as a different request. The caller is responsible
 * for passing an already normalized value.
 */
@Component
public class RequestHasher {

    private final JsonMapper jsonMapper;

    RequestHasher(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String hash(Object normalizedRequest) {
        byte[] canonical = jsonMapper.writeValueAsString(normalizedRequest).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
