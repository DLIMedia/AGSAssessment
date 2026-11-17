package com.ags.transactions.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retention     how long a key is remembered; the spec requires at least 24 hours
 * @param purgeInterval how often expired keys are deleted
 *
 * <p>There is deliberately no "wait timeout" here. A duplicate request waits inside the
 * database, on the first request's uncommitted row, so the bound is the database lock
 * timeout (H2 LOCK_TIMEOUT in the datasource URL; lock_timeout on Postgres,
 * innodb_lock_wait_timeout on MySQL).
 */
@ConfigurationProperties("transactions.idempotency")
public record IdempotencyProperties(
        @DefaultValue("PT24H") Duration retention,
        @DefaultValue("PT1H") Duration purgeInterval) {
}
