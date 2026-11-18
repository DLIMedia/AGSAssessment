# Decisions

## 1. Idempotency lives in the database

The Idempotency-Key is the primary key of an `idempotency_records` table, and I insert that row
in the same database transaction as the bet, record first. The database is the only thing that
can promise "exactly one" across threads and across instances, so there's no in-memory locking.
Both rows commit together, so there's never a half-finished key: either the record and its full
response are there, or nothing is and a retry starts clean.

For two requests with the same key at once, I chose waiting over 409. The second request blocks
on the first one's uncommitted row, then gets a duplicate-key error, reads the stored response
and returns the same 201. A game server retrying a bet after a timeout needs "your bet is
placed", not a conflict to interpret. The wait is capped by the database lock timeout (5
seconds); after that it's 503 with Retry-After and nothing is created. The IETF draft suggests
409 there, but 503 says it's transient and Retry-After tells the client what to do.

The same key with a different body gets 422, because replaying would hide a client bug. I
compare a SHA-256 of the normalized request, so `5` and `5.00` are the same bet. The stored
response is a snapshot, so a retry after the bet has settled still gets the original PENDING
response. Keys are kept for 24 hours and an hourly job deletes older ones.

## 2. Settlement is one conditional UPDATE

`UPDATE ... SET status = ?, win_amount = ? WHERE id = ? AND status = 'PENDING'`. When a complete
and a cancel race, the row lock makes the second one wait; it re-checks the WHERE clause against
the committed row, updates nothing, and I return 409. I picked this over `@Version` because
"only PENDING can change" is written once, in one atomic statement, with no read-then-write and
no retry loop. The entity has no setters for the same reason.

A PATCH retried after it already succeeded also gets 409, even if it's identical. That's what
the spec says, and it keeps "exactly one succeeds" true for identical concurrent requests. The
409 body includes `currentStatus` so the client can see whether its change won.

## 3. One error format

Every error is problem+json (RFC 9457) from one `@RestControllerAdvice` that extends Spring's
`ResponseEntityExceptionHandler`, so malformed JSON or a bad UUID come out the same way.
Validation failures list every bad field, and size limits match the columns so oversized input
is a 400, not a 500 at insert time. The PATCH rules live in the service, because which fields
are allowed depends on the target status.

## How I used AI

I used Claude for the scaffold, for suggesting approaches I then picked from, for drafting tests
I read and tightened, and for explaining Spring and Hibernate behaviour. My rule was that nothing
went in unless I could explain it, and every concurrency claim needed a test against the real
database. That rule caught the first three problems below. I found the fourth by running the
app by hand.

- **It would have silently overwritten keys.** Its entities used assigned ids with a plain
  `save()`, which Spring Data merges, so a second request with the same key would have
  overwritten the first record. I fixed it with `Persistable` and a test that fails without it.
- **It caught the wrong exception.** It caught `DuplicateKeyException`, and a single-threaded
  test agreed. The concurrent test failed 4 of 8 callers, because under concurrency Spring
  reports a plain `DataIntegrityViolationException`. I catch that at the one insert where it
  can happen.
- **It added a polling loop I didn't need.** A test that holds the first request open showed the
  database already does the waiting, so I removed the loop and its timeout setting.
- **It missed a size limit on PATCH.** A `winAmount` too big for the column (more than 17 digits
  before the decimal point) returned 500. POST had the limit; the PATCH rules in the service
  didn't. Both now use one constant, and the new tests fail without the fix.

I rejected its default of 409 for duplicate POSTs and the H2 web console the scaffold added. I
accepted the core design, the latch-based helper that makes test threads actually overlap, and
running the HTTP tests on a real port.

## What's unfinished

I'd move to Postgres with Flyway and run the same concurrency tests on it with Testcontainers,
since locking differs between databases (Postgres waits forever on locks by default). The 503
path is only tested at the service level, and the 24-hour key purge has no test. With more time
I'd add an optional Idempotency-Key on PATCH, scope keys per client once there's authentication,
and add metrics on replays, 409s and lock waits.
