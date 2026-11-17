package com.ags.transactions.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Moves a transaction out of PENDING, only if it is still PENDING.
     *
     * <p>This is the concurrency guard for PATCH. The status check and the write happen in
     * one UPDATE statement, so when two requests race, the database row lock serializes
     * them. The first sees PENDING and updates one row. The second re-checks the WHERE
     * clause against the committed row, matches nothing, and gets 0, which the service
     * turns into 409. No version column or explicit lock is needed.
     *
     * @return 1 if this call won the transition, 0 if the row is missing or already final
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transaction t
               set t.status = :newStatus, t.winAmount = :winAmount
             where t.id = :id
               and t.status = com.ags.transactions.transaction.TransactionStatus.PENDING
            """)
    int settle(@Param("id") UUID id,
               @Param("newStatus") TransactionStatus newStatus,
               @Param("winAmount") BigDecimal winAmount);

    /** Both filters are optional; null means "don't filter on this field". */
    @Query("""
            select t from Transaction t
             where (:playerId is null or t.playerId = :playerId)
               and (:status is null or t.status = :status)
             order by t.createdAt, t.id
            """)
    List<Transaction> search(@Param("playerId") String playerId,
                             @Param("status") TransactionStatus status);
}
