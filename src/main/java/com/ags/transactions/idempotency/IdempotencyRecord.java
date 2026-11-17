package com.ags.transactions.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Remembers the response to a POST made with a given Idempotency-Key.
 *
 * <p>The key is the primary key, so the database itself guarantees that only one request
 * per key can ever commit. This row is inserted in the same database transaction as the
 * {@code Transaction} it created, so the two commit together or not at all. A visible
 * record therefore always has a complete response to replay.
 *
 * <p>The response body is stored as a JSON snapshot, not re-rendered from the transaction.
 * A retry after the bet has settled still gets the original 201 PENDING response, which is
 * what "returns the original response" means. Only successful creates are recorded (always
 * 201), so no status code is stored. Failed requests leave no record and are safe to retry.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String key;

    /** SHA-256 of the request body, used to detect the same key reused with a different body. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Lob
    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The id is assigned by us, not the database, so Spring Data cannot tell a new entity
     * from an existing one and would call merge() (SELECT, then UPDATE if found). For the
     * idempotency record that would silently overwrite a row with the same key instead of
     * failing on the primary key. This flag makes save() always INSERT a new instance.
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
    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String key, String requestHash, UUID transactionId,
                             String responseBody, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.transactionId = transactionId;
        this.responseBody = responseBody;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
    }

    @Override
    public String getId() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
