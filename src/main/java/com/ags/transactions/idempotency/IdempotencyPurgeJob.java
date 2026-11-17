package com.ags.transactions.idempotency;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes idempotency records older than the retention period so the table stays bounded.
 * Keys are honored for at least the retention period; between that and the next purge run
 * they are still honored, which the spec allows ("at least 24 hours"). Once purged, the key
 * can be used again and creates a new transaction.
 */
@Component
class IdempotencyPurgeJob {

    private final IdempotencyRecordRepository records;
    private final IdempotencyProperties properties;
    private final Clock clock;

    IdempotencyPurgeJob(IdempotencyRecordRepository records, IdempotencyProperties properties, Clock clock) {
        this.records = records;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${transactions.idempotency.purge-interval:PT1H}")
    @Transactional
    public int purgeExpired() {
        return records.deleteCreatedBefore(clock.instant().minus(properties.retention()));
    }
}
