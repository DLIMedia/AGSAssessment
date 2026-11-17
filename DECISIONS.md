# Design decisions

> **DRAFT.** Kept up to date as the work goes; to be rewritten in my own words before submission.

## 1. Idempotent create: the database enforces it, and duplicates wait instead of failing

The `Idempotency-Key` is the **primary key** of an `idempotency_records` table. The record and the
transaction are inserted in the **same database transaction**, record first. So:

- Only one request per key can ever commit. This holds across several instances behind a load
  balancer, because the guarantee lives in the shared database, not in application memory.
- A visible record always has a complete response to replay. There is no "claimed but unfinished"
  state to reason about, and a crash before commit leaves nothing behind, so a retry starts clean.
- **Duplicate in flight → block and wait.** A second request with the same key blocks on the
  first one's uncommitted row (H2, Postgres and MySQL all do this). When the first commits, the
  second gets a duplicate-key error, reads the committed record and returns the **original 201
  response**. For a casino wallet integration, a retried bet should get the same successful answer,
  not an error the client has to interpret. The suggested default was 409; I chose to wait instead.
- **The wait is bounded** by the database lock timeout (5s). If it runs out, the caller gets
  **503 + Retry-After** ("nothing created, retry with the same key"), so a stuck request can't hang
  its duplicates forever.
- **Same key, different body → 422**, following the IETF Idempotency-Key draft. Replaying the old
  response would hide a client bug. The comparison uses a SHA-256 of the *normalized* request, so
  `5` and `5.00` are the same request.
- The stored response is a **snapshot**, not re-read from the transaction: a retry after the bet
  settled still gets the original `PENDING` response.
- Keys are kept for 24h (configurable). An hourly job deletes older ones.

## 2. Status changes: one conditional UPDATE

`UPDATE transactions SET status=?, win_amount=? WHERE id=? AND status='PENDING'`. If two PATCHes
race, the database row lock applies them one after the other. The second re-checks `WHERE` against
the committed row, updates 0 rows, and gets **409**. The stored row only ever reflects the winner.
I chose this over optimistic locking (`@Version`) because the rule "only PENDING can change" is
stated once, in the place that is atomic, with no read-modify-write and no retry loop.

## 3. Error model

_TODO: RFC 7807 `ProblemDetail` via one `@RestControllerAdvice`; status code per exception._

## AI usage: accepted, rejected, modified

- **Accepted:** the overall approach (primary-key idempotency table, conditional UPDATE),
  Spring Initializr scaffold, test harness that releases threads with a latch so they really overlap.
- **Rejected:** returning 409 for a concurrent duplicate POST (see decision 1). The H2 web console
  dependency the scaffold adds (unneeded attack surface).
- **Modified / caught:**
  - **Silent overwrite of idempotency keys.** The first draft of the entities let Spring Data
    treat a new record with an assigned id as existing and `merge()` it. A second request with the
    same key would have *overwritten* the first record with no error. Fixed with `Persistable`, and
    a test that fails if the fix is removed (verified by removing it).
  - **Wrong exception type under concurrency.** A single-threaded test showed a duplicate key as
    `DuplicateKeyException`, and the service caught that. The concurrent test then showed the same
    violation arrives as plain `DataIntegrityViolationException` from a concurrent insert, and 4 of
    8 callers got errors. Fixed by catching the violation at the one insert where it can happen.
  - **Removed a polling loop** that would wait for the winner's record after a duplicate key. A
    test that holds the first request open showed the database already does the waiting, so the
    loop was dead code with a second, confusing timeout setting.

## Unfinished / next

- _TODO: HTTP layer, validation, problem+json, README curl examples._
- Open question: should an *identical* retried PATCH (same target, same `winAmount`) after success
  return 200 instead of 409? The spec says a non-PENDING transaction returns 409; currently follows the spec.
- Production: Postgres instead of H2, Flyway migrations instead of `ddl-auto`, and running the
  same concurrency tests against Postgres (Testcontainers), because locking details differ.
