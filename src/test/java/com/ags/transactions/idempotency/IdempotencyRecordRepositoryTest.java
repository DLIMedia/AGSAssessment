package com.ags.transactions.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class IdempotencyRecordRepositoryTest {

    @Autowired
    IdempotencyRecordRepository repository;

    /**
     * Guards the Persistable fix: without it, save() on a second record with the same key
     * would merge (UPDATE) the first one instead of failing, and idempotency would be broken
     * with no error at all.
     */
    @Test
    void secondRecordWithSameKeyIsRejectedNotOverwritten() {
        repository.saveAndFlush(new IdempotencyRecord(
                "key-1", "hash-a", UUID.randomUUID(), 201, "{\"first\":true}", Instant.now()));

        assertThatThrownBy(() -> repository.saveAndFlush(new IdempotencyRecord(
                "key-1", "hash-b", UUID.randomUUID(), 201, "{\"second\":true}", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
