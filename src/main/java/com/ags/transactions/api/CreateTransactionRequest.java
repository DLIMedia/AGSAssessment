package com.ags.transactions.api;

import com.ags.transactions.transaction.NewTransaction;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of POST /transactions.
 *
 * <p>The size limits match the database columns (255 chars; amount precision 19, scale 2, so
 * 17 integer digits). Without them, an over-long value would get past validation and fail at
 * the INSERT as a 500 instead of a 400.
 *
 * <p>{@code @Digits} ignores trailing zeros, so 5.000 is accepted as 5.00 but 5.001 is rejected.
 */
public record CreateTransactionRequest(
        @NotBlank @Size(max = 255) String roundId,
        @NotBlank @Size(max = 255) String playerId,
        @NotBlank @Size(max = 255) String gameName,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal betAmount,
        @NotNull @IsoCurrency String currency) {

    NewTransaction toNewTransaction() {
        return new NewTransaction(roundId, playerId, gameName, betAmount, currency);
    }
}
