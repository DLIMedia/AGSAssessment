package com.ags.transactions.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ags.transactions.transaction.CreateResult;
import com.ags.transactions.transaction.NewTransaction;
import com.ags.transactions.transaction.Transaction;
import com.ags.transactions.transaction.TransactionRepository;
import com.ags.transactions.transaction.TransactionService;
import com.ags.transactions.transaction.TransactionView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the "block and wait" behavior directly, instead of hoping a race happens.
 *
 * <p>A first request is simulated by inserting the idempotency record and transaction in a
 * database transaction that the test holds open. A second, real request with the same key
 * then has to wait on that uncommitted row. A short lock timeout (1s) keeps the timeout
 * test fast; this class gets its own database so it doesn't affect other tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties =
        "spring.datasource.url=jdbc:h2:mem:idempotency-wait;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000")
class IdempotencyWaitTest {

    private static final NewTransaction BET =
            new NewTransaction("round-1", "player-1", "Buffalo Gold", new BigDecimal("5.00"), "USD");

    @Autowired TransactionService service;
    @Autowired TransactionRepository transactions;
    @Autowired IdempotencyRecordRepository idempotencyRecords;
    @Autowired RequestHasher requestHasher;
    @Autowired JsonMapper jsonMapper;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanDatabase() {
        idempotencyRecords.deleteAll();
        transactions.deleteAll();
    }

    /** Starts the "first request" and holds its database transaction open until released. */
    private CompletableFuture<TransactionView> firstRequestHeldOpen(String key, CountDownLatch inserted,
                                                                    CountDownLatch release) {
        return CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactionManager).execute(status -> {
            Transaction tx = Transaction.pending(BET.roundId(), BET.playerId(), BET.gameName(),
                    BET.betAmount(), BET.currency(), Instant.now());
            TransactionView view = TransactionView.from(tx);
            idempotencyRecords.saveAndFlush(new IdempotencyRecord(key, requestHasher.hash(BET.normalized()),
                    tx.getId(), jsonMapper.writeValueAsString(view), Instant.now()));
            transactions.saveAndFlush(tx);
            inserted.countDown();
            await(release);
            return view;
        }));
    }

    @Test
    void duplicateWaitsForFirstRequestToCommitThenReplaysIt() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<TransactionView> first = firstRequestHeldOpen(key, inserted, release);
        assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<CreateResult> second = CompletableFuture.supplyAsync(() -> service.create(key, BET));

        Thread.sleep(300);
        assertThat(second).as("second request is blocked while the first is uncommitted").isNotDone();

        release.countDown();
        CreateResult result = second.get(5, TimeUnit.SECONDS);
        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isEqualTo(first.get());
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void duplicateGivesUpWithInProgressWhenFirstRequestOutlastsLockTimeout() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<TransactionView> first = firstRequestHeldOpen(key, inserted, release);
        assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> service.create(key, BET))
                    .isInstanceOf(IdempotencyKeyInProgressException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        } finally {
            release.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
        assertThat(transactions.count()).as("only the first request's transaction exists").isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
