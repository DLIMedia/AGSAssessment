package com.ags.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ags.transactions.Concurrently;
import com.ags.transactions.Concurrently.Outcome;
import com.ags.transactions.idempotency.IdempotencyKeyReusedException;
import com.ags.transactions.idempotency.IdempotencyRecordRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Service-level tests against the real H2 database. No mocks: the concurrency guarantees come
 * from the database (primary key, row locks), so mocking the repositories would test nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionServiceTest {

    @Autowired
    TransactionService service;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    IdempotencyRecordRepository idempotencyRecords;

    @AfterEach
    void cleanDatabase() {
        idempotencyRecords.deleteAll();
        transactions.deleteAll();
    }

    private static NewTransaction bet(String amount) {
        return new NewTransaction("round-1", "player-1", "Buffalo Gold", new BigDecimal(amount), "USD");
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---- create + idempotency ----

    @Test
    void createStoresPendingTransactionWithNormalizedAmount() {
        CreateResult result = service.create(newKey(), bet("5"));

        assertThat(result.replayed()).isFalse();
        TransactionView view = result.transaction();
        assertThat(view.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(view.betAmount()).isEqualTo(new BigDecimal("5.00"));
        assertThat(view.winAmount()).isNull();
        assertThat(service.get(view.transactionId())).isEqualTo(view);
    }

    @Test
    void repeatedCreateWithSameKeyReplaysOriginalAndCreatesNothing() {
        String key = newKey();
        CreateResult first = service.create(key, bet("5.00"));

        CreateResult second = service.create(key, bet("5"));   // same amount, different spelling

        assertThat(second.replayed()).isTrue();
        assertThat(second.transaction()).isEqualTo(first.transaction());
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String key = newKey();
        service.create(key, bet("5.00"));

        assertThatThrownBy(() -> service.create(key, bet("6.00")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void replayAfterSettlementStillReturnsOriginalPendingResponse() {
        String key = newKey();
        TransactionView original = service.create(key, bet("5.00")).transaction();
        service.settle(original.transactionId(), TransactionStatus.COMPLETED, new BigDecimal("12.50"));

        CreateResult replay = service.create(key, bet("5.00"));

        assertThat(replay.transaction()).isEqualTo(original);
        assertThat(replay.transaction().status()).isEqualTo(TransactionStatus.PENDING);
    }

    /**
     * Required guarantee: simultaneous POSTs with the same key create exactly one transaction.
     * With our "block and wait" choice, every caller also gets the same successful response,
     * not an error.
     */
    @Test
    void concurrentCreatesWithSameKeyCreateExactlyOneAndAllGetTheSameResponse() throws Exception {
        String key = newKey();
        List<Callable<CreateResult>> callers = IntStream.range(0, 8)
                .<Callable<CreateResult>>mapToObj(i -> () -> service.create(key, bet("5.00")))
                .toList();

        List<Outcome<CreateResult>> outcomes = Concurrently.run(callers);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes).extracting(o -> o.value().transaction()).containsOnly(outcomes.get(0).value().transaction());
        assertThat(outcomes).filteredOn(o -> !o.value().replayed()).hasSize(1);
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(idempotencyRecords.count()).isEqualTo(1);
    }

    // ---- settle ----

    @Test
    void completeThenCancelIsRejectedAndTransactionKeepsFirstOutcome() {
        UUID id = service.create(newKey(), bet("5.00")).transaction().transactionId();
        TransactionView completed = service.settle(id, TransactionStatus.COMPLETED, new BigDecimal("12.5"));
        assertThat(completed.winAmount()).isEqualTo(new BigDecimal("12.50"));

        assertThatThrownBy(() -> service.settle(id, TransactionStatus.CANCELLED, null))
                .isInstanceOfSatisfying(TransactionNotPendingException.class,
                        e -> assertThat(e.getCurrentStatus()).isEqualTo(TransactionStatus.COMPLETED));
        assertThat(service.get(id)).isEqualTo(completed);
    }

    @Test
    void cancelledTransactionCannotBeCompleted() {
        UUID id = service.create(newKey(), bet("5.00")).transaction().transactionId();
        service.settle(id, TransactionStatus.CANCELLED, null);

        assertThatThrownBy(() -> service.settle(id, TransactionStatus.COMPLETED, BigDecimal.ZERO))
                .isInstanceOf(TransactionNotPendingException.class);
        assertThat(service.get(id).status()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(service.get(id).winAmount()).isNull();
    }

    @Test
    void settlingUnknownTransactionIsNotFound() {
        assertThatThrownBy(() -> service.settle(UUID.randomUUID(), TransactionStatus.CANCELLED, null))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @ParameterizedTest(name = "{0} with winAmount={1}")
    @CsvSource(nullValues = "null", value = {
            "PENDING,   null",    // can't move back to PENDING
            "COMPLETED, null",    // winAmount required
            "COMPLETED, -1.00",   // negative win
            "COMPLETED, 1.005",   // more than 2 decimals
            "COMPLETED, 100000000000000000.00", // 18 integer digits: too big for the column
            "CANCELLED, 0.00",    // winAmount must not be sent on cancel
    })
    void invalidSettlementIsRejectedAndChangesNothing(TransactionStatus target, BigDecimal winAmount) {
        UUID id = service.create(newKey(), bet("5.00")).transaction().transactionId();

        assertThatThrownBy(() -> service.settle(id, target, winAmount))
                .isInstanceOf(InvalidSettlementException.class);
        assertThat(service.get(id).status()).isEqualTo(TransactionStatus.PENDING);
    }

    /** The largest winAmount the column can hold is accepted, so the size check isn't off by one. */
    @Test
    void largestWinAmountThatFitsTheColumnIsAccepted() {
        UUID id = service.create(newKey(), bet("5.00")).transaction().transactionId();
        BigDecimal largest = new BigDecimal("99999999999999999.99");

        assertThat(service.settle(id, TransactionStatus.COMPLETED, largest).winAmount()).isEqualTo(largest);
        assertThat(service.get(id).winAmount()).isEqualTo(largest);
    }

    /**
     * Required guarantee: a simultaneous complete and cancel on the same PENDING transaction.
     * Exactly one wins, the other gets "not pending" (409), and the stored row matches the
     * winner. Repeated so the two requests genuinely overlap in many rounds, not just one.
     */
    @Test
    void concurrentCompleteAndCancelHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 25; round++) {
            UUID id = service.create(newKey(), bet("5.00")).transaction().transactionId();

            List<Outcome<TransactionView>> outcomes = Concurrently.run(
                    () -> service.settle(id, TransactionStatus.COMPLETED, new BigDecimal("12.50")),
                    () -> service.settle(id, TransactionStatus.CANCELLED, null));

            List<Outcome<TransactionView>> winners = outcomes.stream().filter(Outcome::succeeded).toList();
            List<Outcome<TransactionView>> losers = outcomes.stream().filter(o -> !o.succeeded()).toList();
            assertThat(winners).hasSize(1);
            assertThat(losers).singleElement()
                    .satisfies(o -> assertThat(o.error()).isInstanceOf(TransactionNotPendingException.class));

            TransactionView winner = winners.get(0).value();
            TransactionView stored = service.get(id);
            assertThat(stored).isEqualTo(winner);
            if (stored.status() == TransactionStatus.COMPLETED) {
                assertThat(stored.winAmount()).isEqualTo(new BigDecimal("12.50"));
            } else {
                assertThat(stored.winAmount()).isNull();
            }
        }
    }
}
