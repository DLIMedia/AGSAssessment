package com.ags.transactions.transaction;

import com.ags.transactions.idempotency.IdempotencyKeyInProgressException;
import com.ags.transactions.idempotency.IdempotencyKeyReusedException;
import com.ags.transactions.idempotency.IdempotencyRecord;
import com.ags.transactions.idempotency.IdempotencyRecordRepository;
import com.ags.transactions.idempotency.RequestHasher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final RequestHasher requestHasher;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate inTransaction;
    private final Clock clock;

    TransactionService(TransactionRepository transactions, IdempotencyRecordRepository idempotencyRecords,
                       RequestHasher requestHasher, JsonMapper jsonMapper,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = transactions;
        this.idempotencyRecords = idempotencyRecords;
        this.requestHasher = requestHasher;
        this.jsonMapper = jsonMapper;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Places a bet exactly once per Idempotency-Key.
     *
     * <p>How a duplicate is handled:
     * <ol>
     *   <li>Key already committed: replay the stored response (or 422 if the body differs).</li>
     *   <li>Otherwise insert the idempotency record and the transaction in one database
     *       transaction. The key is the primary key, so only one insert per key can commit.</li>
     *   <li>If another request with the same key is mid-flight, our INSERT blocks on its
     *       uncommitted row. This is the "block and wait": the database does it, bounded by
     *       its lock timeout. When the other request commits, our INSERT fails with a
     *       duplicate key, we read the now-committed record and replay it. If the other
     *       request rolls back instead, our INSERT simply succeeds.</li>
     *   <li>If the lock timeout runs out first, nothing was created and we report 503 so the
     *       client retries with the same key.</li>
     * </ol>
     *
     * <p>The TransactionTemplate is used instead of @Transactional because the duplicate key
     * only surfaces when the transaction flushes, and it has to be caught <em>outside</em>
     * the rolled-back transaction so the replay read runs in a fresh one.
     */
    public CreateResult create(String idempotencyKey, NewTransaction request) {
        NewTransaction normalized = request.normalized();
        String requestHash = requestHasher.hash(normalized);

        var existing = idempotencyRecords.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        try {
            TransactionView created = inTransaction.execute(status -> insertNew(idempotencyKey, requestHash, normalized));
            return new CreateResult(created, false);
        } catch (IdempotencyKeyTaken lostTheRace) {
            // Another request with this key committed first, so its record is now visible.
            IdempotencyRecord winner = idempotencyRecords.findById(idempotencyKey)
                    .orElseThrow(() -> new IdempotencyKeyInProgressException(idempotencyKey, lostTheRace));
            return replay(winner, requestHash);
        } catch (PessimisticLockingFailureException lockTimeout) {
            throw new IdempotencyKeyInProgressException(idempotencyKey, lockTimeout);
        }
    }

    private TransactionView insertNew(String idempotencyKey, String requestHash, NewTransaction request) {
        Instant now = clock.instant();
        Transaction transaction = Transaction.pending(request.roundId(), request.playerId(), request.gameName(),
                request.betAmount(), request.currency(), now);
        TransactionView view = TransactionView.from(transaction);

        // Record first: a duplicate key fails (or waits) here, before any transaction row is written.
        try {
            idempotencyRecords.saveAndFlush(new IdempotencyRecord(idempotencyKey, requestHash, transaction.getId(),
                    jsonMapper.writeValueAsString(view), now));
        } catch (DataIntegrityViolationException e) {
            // Caught here, at this one insert, rather than relying on the exception subtype:
            // Spring reports the same H2 primary-key violation as DuplicateKeyException in a
            // single-threaded test but as plain DataIntegrityViolationException when it comes
            // from a concurrent insert. Every other column is non-null and set by us, and the
            // key length is validated at the HTTP boundary, so the primary key is the only
            // constraint this insert can break.
            throw new IdempotencyKeyTaken(e);
        }
        transactions.save(transaction);
        return view;
    }

    /** Internal signal that another request owns this key; never leaves this class. */
    private static final class IdempotencyKeyTaken extends RuntimeException {
        IdempotencyKeyTaken(Throwable cause) {
            super(cause);
        }
    }

    private CreateResult replay(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(record.getId());
        }
        return new CreateResult(jsonMapper.readValue(record.getResponseBody(), TransactionView.class), true);
    }

    public TransactionView get(UUID id) {
        return transactions.findById(id).map(TransactionView::from)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    /** Both filters are optional. */
    public List<TransactionView> list(String playerId, TransactionStatus status) {
        return transactions.search(playerId, status).stream().map(TransactionView::from).toList();
    }

    /**
     * Completes or cancels a PENDING transaction.
     *
     * <p>The conditional UPDATE in {@link TransactionRepository#settle} is the whole concurrency
     * story: if two requests race, the second blocks on the row lock until the first commits,
     * then matches 0 rows. We only read the row afterwards to tell 404 from 409 and to build
     * the response, inside the same transaction so we return exactly what we wrote.
     */
    public TransactionView settle(UUID id, TransactionStatus target, BigDecimal winAmount) {
        BigDecimal normalizedWin = validateSettlement(target, winAmount);
        return inTransaction.execute(status -> {
            int updated = transactions.settle(id, target, normalizedWin);
            Transaction current = transactions.findById(id).orElseThrow(() -> new TransactionNotFoundException(id));
            if (updated == 0) {
                throw new TransactionNotPendingException(id, current.getStatus());
            }
            return TransactionView.from(current);
        });
    }

    /**
     * Domain rules for the PATCH body. The HTTP layer checks types and formats; these rules
     * live here because they are about the lifecycle, not about JSON.
     */
    private static BigDecimal validateSettlement(TransactionStatus target, BigDecimal winAmount) {
        if (target == null || target == TransactionStatus.PENDING) {
            throw new InvalidSettlementException("status must be COMPLETED or CANCELLED");
        }
        if (target == TransactionStatus.CANCELLED) {
            if (winAmount != null) {
                throw new InvalidSettlementException("winAmount must not be sent when cancelling");
            }
            return null;
        }
        if (winAmount == null) {
            throw new InvalidSettlementException("winAmount is required when completing");
        }
        if (winAmount.signum() < 0) {
            throw new InvalidSettlementException("winAmount must be 0.00 or more");
        }
        if (winAmount.stripTrailingZeros().scale() > 2) {
            throw new InvalidSettlementException("winAmount must have at most 2 decimal places");
        }
        return winAmount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
