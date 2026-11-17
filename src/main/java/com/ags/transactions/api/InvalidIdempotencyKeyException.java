package com.ags.transactions.api;

/** The Idempotency-Key header is missing, blank or longer than 255 characters (400). */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
