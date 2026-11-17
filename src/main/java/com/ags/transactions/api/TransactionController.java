package com.ags.transactions.api;

import com.ags.transactions.transaction.CreateResult;
import com.ags.transactions.transaction.TransactionService;
import com.ags.transactions.transaction.TransactionStatus;
import com.ags.transactions.transaction.TransactionView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP mapping only: parse and validate input, call the service, shape the response. All
 * idempotency and concurrency logic lives in TransactionService; errors are turned into
 * problem+json responses by ApiExceptionHandler.
 */
@RestController
@RequestMapping("/transactions")
class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    /** Set on a replayed POST so clients and logs can tell a replay from a fresh create. */
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    /** Same limit as the idempotency_records primary key column. */
    static final int MAX_KEY_LENGTH = 255;

    private final TransactionService service;

    TransactionController(TransactionService service) {
        this.service = service;
    }

    /**
     * A replay returns the same status (201), Location and body as the original request, so a
     * client that retries cannot tell the difference except by the Idempotent-Replayed header.
     */
    @PostMapping
    ResponseEntity<TransactionView> create(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                           @Valid @RequestBody CreateTransactionRequest body) {
        requireValidKey(idempotencyKey);
        CreateResult result = service.create(idempotencyKey, body.toNewTransaction());
        TransactionView view = result.transaction();

        var response = ResponseEntity.created(URI.create("/transactions/" + view.transactionId()));
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(view);
    }

    /**
     * Checked here rather than with Bean Validation on the header parameter: constraints on a
     * controller parameter switch Spring to method validation, which reports body and header
     * errors through a different exception type. One explicit check is easier to follow.
     */
    private static void requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException("The " + IDEMPOTENCY_KEY + " header is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "The " + IDEMPOTENCY_KEY + " header must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    @GetMapping("/{id}")
    TransactionView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    List<TransactionView> list(@RequestParam(required = false) String playerId,
                               @RequestParam(required = false) TransactionStatus status) {
        return service.list(playerId, status);
    }

    @PatchMapping("/{id}")
    TransactionView settle(@PathVariable UUID id, @RequestBody SettleTransactionRequest body) {
        return service.settle(id, body.status(), body.winAmount());
    }
}
