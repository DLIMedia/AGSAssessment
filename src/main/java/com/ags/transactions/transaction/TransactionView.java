package com.ags.transactions.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The transaction as returned by the API. Kept separate from the entity so the JSON contract
 * doesn't change when the persistence mapping does. This is also the shape stored in the
 * idempotency snapshot.
 */
public record TransactionView(UUID transactionId, String roundId, String playerId, String gameName,
                              BigDecimal betAmount, BigDecimal winAmount, String currency,
                              TransactionStatus status, Instant createdAt) {

    public static TransactionView from(Transaction t) {
        return new TransactionView(t.getId(), t.getRoundId(), t.getPlayerId(), t.getGameName(),
                t.getBetAmount(), t.getWinAmount(), t.getCurrency(), t.getStatus(), t.getCreatedAt());
    }
}
