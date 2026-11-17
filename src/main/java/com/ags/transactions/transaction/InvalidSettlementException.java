package com.ags.transactions.transaction;

/** The PATCH body is not a valid complete or cancel request (400). */
public class InvalidSettlementException extends RuntimeException {

    public InvalidSettlementException(String message) {
        super(message);
    }
}
