package com.ags.transactions.transaction;

/**
 * Lifecycle of a wager: PENDING when the bet is placed, then exactly one move to a
 * final state. COMPLETED and CANCELLED never change again.
 */
public enum TransactionStatus {
    PENDING,
    COMPLETED,
    CANCELLED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
