package com.ags.transactions.idempotency;

/**
 * The key was already used for a different request body. Replaying the old response would
 * hide a client bug, so this is reported (as 422, following the IETF Idempotency-Key draft).
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String key;

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
