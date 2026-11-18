# Design decisions

## 1. Idempotent create: the database enforces it, and duplicates wait instead of failing

The `Idempotency-Key` is the **primary key** of an `idempotency_records` table, inserted in the
**same database transaction** as the transaction it creates, record first.

- **Only one request per key can commit**, even across several instances behind a load balancer,
  because the guarantee lives in the shared database, not in memory. A visible record always has a
  complete response to replay; a crash before commit leaves nothing behind, so a retry starts clean.
- **A duplicate in flight blocks and waits** on the first request's uncommitted row, then replays
  the committed **original 201**. A retried bet should get the same successful answer, not an error
  the client has to interpret. The suggested default was 409; I chose to wait instead.
- **The wait is bounded** by the database lock timeout (5s), then **503 + Retry-After**: nothing
  created, retry with the same key.
- **Same key, different body → 422** (IETF Idempotency-Key draft); replaying would hide a client
  bug. The body is compared by SHA-256 of the *normalized* request, so `5` and `5.00` match.
- The stored response is a **snapshot**: a retry after settlement still gets the original
  `PENDING` response. Keys are kept 24h (configurable); an hourly job deletes older ones.
- If the commit succeeds but the response is lost, the client retries with the same key and gets
  the original response. That is the case idempotency exists for.

## 2. Status changes: one conditional UPDATE

`UPDATE transactions SET status=?, win_amount=? WHERE id=? AND status='PENDING'`. When two PATCHes
race, the row lock applies them in turn; the second re-checks `WHERE`, updates 0 rows and gets
**409**, and the row reflects only the winner. I chose this over optimistic locking (`@Version`)
because "only PENDING can change" is stated once, in the atomic statement, with no
read-modify-write and no retry loop.

**An identical retried PATCH also gets 409.** The spec says a non-PENDING transaction cannot change,
and that of two simultaneous PATCHes exactly one succeeds; a 200 for matching retries would break
that for two identical concurrent requests. PATCH has no Idempotency-Key, so a client that gets
409 should GET the transaction; the 409 body carries `currentStatus` to help.

## 3. Error model: problem+json, one status per exception

One `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`, so Spring's own errors
(malformed JSON, non-UUID id) share the RFC 9457 format. **400** validation (with a
`{field, message}` list), bad `Idempotency-Key`, invalid PATCH body; **404** not found; **409** not
PENDING; **422** key reused; **503 + Retry-After** key still in flight; **500** unexpected (logged,
generic message).

- POST body: Bean Validation on a DTO, with `@Size` limits matching the columns so an over-long
  value is a 400, not a 500 at INSERT. Currency is checked against `java.util.Currency`.
- PATCH body rules live in the service, because which fields are allowed depends on the target
  status, a lifecycle rule.
- The `Idempotency-Key` header is checked with a few explicit lines in the controller. Constraint
  annotations on the parameter would switch Spring to method validation, which reports header and
  body errors through a different exception type.

## AI usage: accepted, rejected, modified

- **Accepted:** the core approach (primary-key idempotency table, conditional UPDATE); the
  scaffold; a test helper that releases threads with a latch so they really overlap; HTTP tests on
  a real port instead of MockMvc, so concurrent requests use Tomcat's threads; 409 for an identical
  PATCH retry, after checking the spec wording.
- **Rejected:** 409 for a concurrent duplicate POST (see 1); the H2 web console the scaffold adds.
- **Caught and fixed:**
  - **Silent overwrite of idempotency keys.** The first entity draft let Spring Data `merge()` a
    new record with an assigned id, so a second request would overwrite the first with no error.
    Fixed with `Persistable`; a test fails if the fix is removed (verified).
  - **Wrong exception type under concurrency.** A single-threaded test showed `DuplicateKeyException`;
    the concurrent test showed plain `DataIntegrityViolationException`, and 4 of 8 callers failed.
    Fixed by catching the violation at the one insert where it can happen.
  - **A redundant polling loop** for the winner's record. A test holding the first request open
    showed the database already waits, so the loop was dead code with a confusing second timeout.

## Unfinished / next

- Postgres instead of H2, Flyway instead of `ddl-auto`, and the same concurrency tests against
  Postgres via Testcontainers, because locking details differ between databases.
- The 503 path is tested at the service level (`IdempotencyWaitTest`), not over HTTP.
- Out of scope but next in production: authentication, metrics/tracing on replays, 409s and
  503s, and a health endpoint.
