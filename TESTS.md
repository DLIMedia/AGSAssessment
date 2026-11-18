# Test results

**58 tests, 58 passed, 0 failed, 0 skipped.**

| | |
|---|---|
| Command | `./mvnw clean test` |
| Run on | 2026-11-18, OpenJDK 25.0.5 on Linux (the project targets Java 17) |
| Database | In-memory H2 through Spring Boot. No repository or database is mocked. |
| Build time | 24.6 s |

| Test class | Level | Tests | Result |
|---|---|---|---|
| `TransactionApiTest` | HTTP calls to the app on a real port | 35 | pass |
| `TransactionServiceTest` | Service + H2 | 16 | pass |
| `TransactionRepositoryTest` | Repository + H2 | 3 | pass |
| `IdempotencyWaitTest` | Service + H2, holds a database transaction open | 2 | pass |
| `IdempotencyRecordRepositoryTest` | Repository + H2 | 1 | pass |
| `TransactionsServiceApplicationTests` | Spring context | 1 | pass |

The log shows `Unique index or primary key violation` warnings during the run. They are
expected: the concurrent-POST tests send many requests with the same Idempotency-Key, and the
database rejects every insert after the first. That rejection is how a duplicate request is
made to wait and then replay the original response.

## Spec requirements and the tests that cover them

| Requirement | Tests |
|---|---|
| Two simultaneous POSTs with the same key create exactly one transaction | `TransactionApiTest.concurrentPostsWithSameKeyCreateExactlyOneTransaction`, `TransactionServiceTest.concurrentCreatesWithSameKeyCreateExactlyOneAndAllGetTheSameResponse` |
| A duplicate POST that arrives mid-flight waits, then gets the original response | `IdempotencyWaitTest.duplicateWaitsForFirstRequestToCommitThenReplaysIt`; when the wait times out, `duplicateGivesUpWithInProgressWhenFirstRequestOutlastsLockTimeout` (503, nothing created) |
| Simultaneous complete and cancel: exactly one wins, the other gets 409, and the stored row is the winner's | `TransactionApiTest.concurrentCompleteAndCancelHaveExactlyOneWinner`, `TransactionServiceTest.concurrentCompleteAndCancelHaveExactlyOneWinner` (20 and 25 rounds) |
| A repeated POST returns the original response and creates nothing | `TransactionApiTest.repeatedPostWithSameKeyReturnsOriginalResponseAndCreatesNothing`, `TransactionServiceTest.repeatedCreateWithSameKeyReplaysOriginalAndCreatesNothing`, `replayAfterSettlementStillReturnsOriginalPendingResponse` |
| Same key with a different body (decided: 422) | `TransactionApiTest.sameKeyWithDifferentBodyReturns422`, `TransactionServiceTest.sameKeyWithDifferentBodyIsRejected` |
| A key can never be overwritten | `IdempotencyRecordRepositoryTest.secondRecordWithSameKeyIsRejectedNotOverwritten` |
| Missing Idempotency-Key returns 400 | `TransactionApiTest.missingIdempotencyKeyReturns400`, `overLongIdempotencyKeyReturns400` |
| COMPLETED and CANCELLED are final (409) | `TransactionApiTest.finalTransactionCannotChange` (3 cases), `TransactionServiceTest.completeThenCancelIsRejectedAndTransactionKeepsFirstOutcome`, `cancelledTransactionCannotBeCompleted`, `TransactionRepositoryTest.settleDoesNothingOnceTransactionIsFinal` |
| Validation: required fields, betAmount > 0, ISO 4217 currency, at most 2 decimal places | `TransactionApiTest.invalidCreateBodyReturns400WithTheField` (10 cases), `malformedJsonReturns400` |
| PATCH rules: winAmount required to complete, not allowed when cancelling | `TransactionApiTest.invalidSettlementReturns400AndChangesNothing` (8 cases), `TransactionServiceTest.invalidSettlementIsRejectedAndChangesNothing` (6 cases) |
| 200 / 201 / 404 status codes, problem+json errors | `createReturns201WithLocationAndPendingTransaction`, `getReturnsTheTransaction`, `completeSetsWinAmount`, `cancelLeavesWinAmountNull`, `getUnknownIdReturns404AndNonUuidReturns400`, `patchUnknownIdReturns404` |
| List filtered by playerId and/or status | `TransactionApiTest.listFiltersByPlayerAndStatus`, `TransactionRepositoryTest.searchFiltersByPlayerAndStatusIndependently` |

Not covered by an automated test: the hourly job that removes keys older than 24 hours
(`IdempotencyPurgeJob`), and the 503 path over HTTP (it is tested at the service level only).

## Every test

### TransactionApiTest (35)

| Test | Cases |
|---|---|
| `createReturns201WithLocationAndPendingTransaction` | |
| `repeatedPostWithSameKeyReturnsOriginalResponseAndCreatesNothing` | |
| `concurrentPostsWithSameKeyCreateExactlyOneTransaction` | |
| `sameKeyWithDifferentBodyReturns422` | |
| `missingIdempotencyKeyReturns400` | |
| `overLongIdempotencyKeyReturns400` | 256 characters rejected, 255 accepted |
| `invalidCreateBodyReturns400WithTheField` | betAmount `0`, `-1.00`, `1.001`, `null`; currency `"usd"`, `"ABC"`, `null`; roundId `""`; playerId `null`; gameName `"   "` |
| `malformedJsonReturns400` | |
| `getReturnsTheTransaction` | |
| `getUnknownIdReturns404AndNonUuidReturns400` | |
| `listFiltersByPlayerAndStatus` | |
| `completeSetsWinAmount` | |
| `cancelLeavesWinAmountNull` | |
| `finalTransactionCannotChange` | complete then cancel; cancel then complete; the same complete sent twice |
| `invalidSettlementReturns400AndChangesNothing` | COMPLETED with no winAmount; winAmount `-1`; winAmount `1.001`; winAmount `100000000000000000000` (too big for the column); CANCELLED with a winAmount; status PENDING; unknown status; empty body |
| `patchUnknownIdReturns404` | |
| `concurrentCompleteAndCancelHaveExactlyOneWinner` | 20 rounds |

### TransactionServiceTest (16)

| Test | Cases |
|---|---|
| `createStoresPendingTransactionWithNormalizedAmount` | |
| `repeatedCreateWithSameKeyReplaysOriginalAndCreatesNothing` | |
| `sameKeyWithDifferentBodyIsRejected` | |
| `replayAfterSettlementStillReturnsOriginalPendingResponse` | |
| `concurrentCreatesWithSameKeyCreateExactlyOneAndAllGetTheSameResponse` | |
| `completeThenCancelIsRejectedAndTransactionKeepsFirstOutcome` | |
| `cancelledTransactionCannotBeCompleted` | |
| `settlingUnknownTransactionIsNotFound` | |
| `invalidSettlementIsRejectedAndChangesNothing` | back to PENDING; COMPLETED with no winAmount; `-1.00`; `1.005`; `100000000000000000.00` (18 integer digits); CANCELLED with `0.00` |
| `largestWinAmountThatFitsTheColumnIsAccepted` | `99999999999999999.99` |
| `concurrentCompleteAndCancelHaveExactlyOneWinner` | 25 rounds |

### TransactionRepositoryTest (3)

- `settleMovesPendingToCompletedWithWinAmount`
- `settleDoesNothingOnceTransactionIsFinal`
- `searchFiltersByPlayerAndStatusIndependently`

### IdempotencyWaitTest (2)

- `duplicateWaitsForFirstRequestToCommitThenReplaysIt`
- `duplicateGivesUpWithInProgressWhenFirstRequestOutlastsLockTimeout`

### IdempotencyRecordRepositoryTest (1)

- `secondRecordWithSameKeyIsRejectedNotOverwritten`

### TransactionsServiceApplicationTests (1)

- `contextLoads`

## Manual check against the running app

As a final check against the spec, I started the packaged jar and ran the README curl
commands plus some edge cases:

- Create, replay, same key with a different body, missing key, get, filtered list, complete,
  and cancel after complete returned 201, 201 + `Idempotent-Replayed`, 422, 400, 200, 200, 200
  and 409, as documented.
- Bad input returned problem+json with the right status: an unknown status filter 400, a
  string winAmount 400, a missing PATCH body 400, an oversized betAmount 400, an unsupported
  method 405, and a wrong Content-Type 415.
- **Bug found:** completing with a `winAmount` larger than the column holds returned **500**.
  It now returns 400 and is covered by the new test cases above (see DECISIONS.md).
