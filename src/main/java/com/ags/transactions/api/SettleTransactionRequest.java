package com.ags.transactions.api;

import com.ags.transactions.transaction.TransactionStatus;
import java.math.BigDecimal;

/**
 * Body of PATCH /transactions/{id}: {"status": "COMPLETED", "winAmount": 12.50} or
 * {"status": "CANCELLED"}.
 *
 * <p>No Bean Validation annotations on purpose. Which fields are allowed depends on the target
 * status (winAmount required to complete, forbidden to cancel), which is a lifecycle rule, so
 * all of it is checked in one place: TransactionService.validateSettlement. An unknown status
 * value fails JSON parsing and becomes a 400 there.
 */
public record SettleTransactionRequest(TransactionStatus status, BigDecimal winAmount) {
}
