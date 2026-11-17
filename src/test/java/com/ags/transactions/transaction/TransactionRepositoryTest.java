package com.ags.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * Single-threaded checks of the repository queries against real H2. The concurrent
 * guarantees are proven separately, end to end, once the HTTP layer exists.
 */
@DataJpaTest
class TransactionRepositoryTest {

    @Autowired
    TransactionRepository repository;

    private Transaction savePending(String playerId) {
        return repository.saveAndFlush(Transaction.pending(
                "round-1", playerId, "Buffalo Gold", new BigDecimal("5.00"), "USD", Instant.now()));
    }

    @Test
    void settleMovesPendingToCompletedWithWinAmount() {
        Transaction tx = savePending("player-1");

        int updated = repository.settle(tx.getId(), TransactionStatus.COMPLETED, new BigDecimal("12.50"));

        assertThat(updated).isEqualTo(1);
        Transaction reloaded = repository.findById(tx.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(reloaded.getWinAmount()).isEqualByComparingTo("12.50");
    }

    @Test
    void settleDoesNothingOnceTransactionIsFinal() {
        Transaction tx = savePending("player-1");
        repository.settle(tx.getId(), TransactionStatus.CANCELLED, null);

        int updated = repository.settle(tx.getId(), TransactionStatus.COMPLETED, new BigDecimal("99.00"));

        assertThat(updated).isZero();
        Transaction reloaded = repository.findById(tx.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(reloaded.getWinAmount()).isNull();
    }

    @Test
    void searchFiltersByPlayerAndStatusIndependently() {
        Transaction a = savePending("player-1");
        Transaction b = savePending("player-1");
        Transaction c = savePending("player-2");
        repository.settle(b.getId(), TransactionStatus.CANCELLED, null);

        assertThat(repository.search(null, null)).extracting(Transaction::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(repository.search("player-1", null)).extracting(Transaction::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(repository.search(null, TransactionStatus.PENDING)).extracting(Transaction::getId)
                .containsExactlyInAnyOrder(a.getId(), c.getId());
        assertThat(repository.search("player-1", TransactionStatus.CANCELLED)).extracting(Transaction::getId)
                .containsExactly(b.getId());
    }
}
