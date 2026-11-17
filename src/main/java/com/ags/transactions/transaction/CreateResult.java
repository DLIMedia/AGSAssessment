package com.ags.transactions.transaction;

/**
 * @param replayed true when this is the stored response of an earlier request with the same
 *                 Idempotency-Key, and nothing new was created
 */
public record CreateResult(TransactionView transaction, boolean replayed) {
}
