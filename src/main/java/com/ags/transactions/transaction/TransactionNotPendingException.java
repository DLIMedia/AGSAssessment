package com.ags.transactions.transaction;

import java.util.UUID;

/** The transaction is already COMPLETED or CANCELLED and can't change (409). */
public class TransactionNotPendingException extends RuntimeException {

    private final UUID transactionId;
    private final TransactionStatus currentStatus;

    public TransactionNotPendingException(UUID transactionId, TransactionStatus currentStatus) {
        super("Transaction " + transactionId + " is already " + currentStatus + " and cannot be changed");
        this.transactionId = transactionId;
        this.currentStatus = currentStatus;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public TransactionStatus getCurrentStatus() {
        return currentStatus;
    }
}
