package com.ags.transactions.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Service-level input for placing a bet. Field-level validation (required, > 0, ISO 4217,
 * 2 decimals) happens at the HTTP boundary before this is built.
 */
public record NewTransaction(String roundId, String playerId, String gameName,
                             BigDecimal betAmount, String currency) {

    /** The form that is hashed for idempotency, so "5" and "5.00" count as the same request. */
    public NewTransaction normalized() {
        return new NewTransaction(roundId, playerId, gameName,
                betAmount.setScale(2, RoundingMode.UNNECESSARY), currency);
    }
}
