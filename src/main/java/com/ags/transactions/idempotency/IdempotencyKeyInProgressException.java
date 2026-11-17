package com.ags.transactions.idempotency;

/**
 * A request with the same key was still in flight when our bounded wait ran out. Nothing was
 * created by this request; the client should retry with the same key (503 + Retry-After).
 */
public class IdempotencyKeyInProgressException extends RuntimeException {

    private final String key;

    public IdempotencyKeyInProgressException(String key, Throwable cause) {
        super("A request with Idempotency-Key '" + key + "' is still being processed; retry with the same key", cause);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
