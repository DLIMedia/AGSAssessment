package com.ags.transactions.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A single wager and its outcome.
 *
 * <p>The entity has no setters. It is created PENDING through {@link #pending}, and the
 * only change it ever gets (PENDING to COMPLETED or CANCELLED) goes through
 * {@link TransactionRepository#settle}, a conditional UPDATE that the database applies
 * atomically. That keeps the "only PENDING can change" rule in one place that is safe
 * under concurrent requests, rather than in a read-modify-write on this object.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_transactions_player_status", columnList = "player_id, status")
})
public class Transaction implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "round_id", nullable = false)
    private String roundId;

    @Column(name = "player_id", nullable = false)
    private String playerId;

    @Column(name = "game_name", nullable = false)
    private String gameName;

    @Column(name = "bet_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal betAmount;

    /** Null while PENDING and when CANCELLED; 0.00 or more once COMPLETED. */
    @Column(name = "win_amount", precision = 19, scale = 2)
    private BigDecimal winAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The id is assigned by us, not the database, so Spring Data cannot tell a new entity
     * from an existing one and would call merge(): an extra SELECT before every insert, and
     * a silent overwrite if an id ever collided. This flag makes save() always INSERT a new
     * instance. (See IdempotencyRecord, where the overwrite would break idempotency.)
     */
    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    /** For JPA only. */
    protected Transaction() {
    }

    private Transaction(UUID id, String roundId, String playerId, String gameName,
                        BigDecimal betAmount, String currency, Instant createdAt) {
        this.id = id;
        this.roundId = roundId;
        this.playerId = playerId;
        this.gameName = gameName;
        this.betAmount = betAmount;
        this.currency = currency;
        this.status = TransactionStatus.PENDING;
        this.createdAt = createdAt;
    }

    /**
     * Creates a new PENDING transaction. The id and timestamp are assigned here, not by the
     * database, so the full response is known before commit. The idempotency record stores
     * that response in the same database transaction.
     *
     * <p>createdAt is truncated to milliseconds and betAmount is set to scale 2 so the values
     * we return now match what the database hands back on later reads ("5" becomes "5.00").
     * RoundingMode.UNNECESSARY throws instead of silently rounding if more than 2 decimals
     * ever slip past validation.
     */
    public static Transaction pending(String roundId, String playerId, String gameName,
                                      BigDecimal betAmount, String currency, Instant now) {
        return new Transaction(UUID.randomUUID(), roundId, playerId, gameName,
                betAmount.setScale(2, RoundingMode.UNNECESSARY), currency,
                now.truncatedTo(ChronoUnit.MILLIS));
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getRoundId() {
        return roundId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getGameName() {
        return gameName;
    }

    public BigDecimal getBetAmount() {
        return betAmount;
    }

    public BigDecimal getWinAmount() {
        return winAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
