# Decisions

## 1. Idempotency lives in the database

I made the Idempotency-Key the primary key of an `idempotency_records` table and insert that row
in the same database transaction as the bet itself, record first. My reasoning was that the
database is the only thing that can promise "exactly one" across threads and across instances,
so I didn't want any in-memory locking or a separate lock service. Because both rows commit
together, there's never a half-finished key to deal with: either the record and its full
response are there, or nothing is, and a retry starts clean.

The spec let me choose what happens when two requests with the same key arrive at once. I went
with waiting instead of returning 409. The second request blocks on the first one's uncommitted
row, and once the first commits it gets a duplicate-key error, reads the stored response and
returns the same 201. If a game server retries a bet because of a timeout, what it needs is
"your bet is placed", not a conflict it has to interpret. The wait is capped by the database
lock timeout (5 seconds); after that the caller gets 503 with Retry-After and nothing is created.
I know the IETF draft suggests 409 for this case. I chose 503 because it's transient and
Retry-After tells the client exactly what to do.

Reusing a key with a different body gets 422. Replaying the old response there would hide a
client bug. I compare a SHA-256 of the normalized request, so `5` and `5.00` count as the same
bet. I store the original response as a snapshot, so a retry after the bet has settled still
gets the original PENDING response. Keys are kept for 24 hours and an hourly job cleans them up.

## 2. Settlement is one conditional UPDATE

`UPDATE ... SET status = ?, win_amount = ? WHERE id = ? AND status = 'PENDING'`. When a complete
and a cancel race, the row lock makes the second one wait, it re-checks the WHERE clause against
the committed row, updates nothing, and I turn that into a 409. I picked this over `@Version`
optimistic locking because the rule "only PENDING can change" is written once, in the one
statement that's atomic, with no read-then-write and no retry loop. The entity has no setters
for the same reason.

A PATCH that's retried after it already succeeded also gets 409, even if it's identical. That's
what the spec says, and it keeps "exactly one succeeds" true for two identical concurrent
requests. The 409 body includes `currentStatus` so the client can see whether its change won.

## 3. One error format

Every error is problem+json (RFC 9457) from a single `@RestControllerAdvice`, extending Spring's
`ResponseEntityExceptionHandler` so malformed JSON or a bad UUID come out in the same format.
Validation failures list every bad field. The POST body uses Bean Validation, with size limits
that match the columns so an over-long value is a 400 instead of a 500 at insert time. The PATCH
rules live in the service, because which fields are allowed depends on the target status. I
check the Idempotency-Key header with a few plain lines in the controller rather than
annotations, because annotating it switches Spring to method validation and a different
exception type, which is harder to follow.

## How I used AI

I used Claude for the scaffold, for suggesting approaches that I then picked from, for drafting
tests that I read and tightened, and for explaining Spring and Hibernate behaviour. My rule was
that nothing went in unless I could explain it, and every concurrency claim had to be proven by
a test against the real database. That rule is what caught the first three problems below. The
fourth got past it, and I only found it by running the app by hand.

- **It would have silently overwritten keys.** The first version of the entities used assigned
  ids with a plain `save()`. Spring Data treats an assigned id as "existing" and merges it, so a
  second request with the same key would have overwritten the first record with no error. I
  fixed it with `Persistable`, wrote a test for it, and checked that the test fails without the
  fix.
- **It caught the wrong exception.** It caught `DuplicateKeyException`, and a single-threaded
  test agreed. The concurrent test didn't: 4 of 8 callers failed, because under concurrency
  Spring reports the same violation as a plain `DataIntegrityViolationException`. Now I catch
  that at the one insert where it can happen.
- **It added a polling loop I didn't need.** It suggested polling for the winner's record after
  a duplicate key. A test that holds the first request open showed the database was already
  doing the waiting, so I removed the loop and the extra timeout setting that came with it.
- **It missed a size limit on PATCH.** For my final check against the spec I ran the README
  curls plus some edge cases against the running app. Completing a bet with a `winAmount` too
  big for the column (more than 17 digits before the decimal point) came back as a 500. The
  POST body already had that limit, but the PATCH rules are checked in the service, and nobody
  had added it there. Now both use the same constant, so it's a 400. I added a test for the
  rejected value and one for the largest value that fits, and checked the rejection tests fail
  without the fix. The tests had only covered the cases I thought to write down.

I rejected its default of 409 for duplicate POSTs (I wanted the wait), and the H2 web console
the scaffold added. I accepted the core design, the latch-based helper that makes test threads
actually overlap, and running the HTTP tests on a real port.

## What's unfinished

I'd move to Postgres with Flyway, and run the same concurrency tests against it with
Testcontainers, since locking details differ between databases (Postgres waits forever on locks
by default, for one). The 503 path is only tested at the service level, not over HTTP. With more
time I'd also add an optional Idempotency-Key on PATCH, scope keys per client once there's
authentication, and add metrics on replays, 409s and lock waits.
