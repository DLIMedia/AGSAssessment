# AGS Transactions Service

Spring Boot service for recording casino wagers (PENDING → COMPLETED / CANCELLED) with
idempotent creation and safe concurrent status changes. Design choices are in
[DECISIONS.md](DECISIONS.md).

## Requirements

- JDK 17 or newer (no Maven install needed; the Maven wrapper is included)

## Build, test, run

```bash
./mvnw test             # run the test suite (unit, service and HTTP-level tests against real H2)
./mvnw package          # build target/transactions-service-0.0.1-SNAPSHOT.jar
./mvnw spring-boot:run  # start on http://localhost:8080
```

The database is in-memory H2, so data is lost when the service stops.

## API

| Method | Path | Success | Errors |
|--------|------|---------|--------|
| POST | `/transactions` (requires `Idempotency-Key` header) | 201 + `Location` | 400, 422, 503 |
| GET | `/transactions/{id}` | 200 | 400, 404 |
| GET | `/transactions?playerId=&status=` (both optional) | 200 | 400 |
| PATCH | `/transactions/{id}` | 200 | 400, 404, 409 |

Errors are `application/problem+json` (RFC 9457) with `status`, `title`, `detail`, `instance`,
plus `errors` (field-level validation failures) or `currentStatus` (409) where relevant.

### Create (with Idempotency-Key)

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7f3c2a10-bet-0001' \
  -d '{"roundId":"round-1","playerId":"player-1","gameName":"Buffalo Gold","betAmount":5.00,"currency":"USD"}'
```

```
HTTP/1.1 201
Location: /transactions/32971cfb-8282-4bd0-bd66-652a0f96d15e

{"transactionId":"32971cfb-8282-4bd0-bd66-652a0f96d15e","roundId":"round-1","playerId":"player-1",
 "gameName":"Buffalo Gold","betAmount":5.00,"winAmount":null,"currency":"USD","status":"PENDING",
 "createdAt":"2026-11-17T23:30:58.090Z"}
```

Sending the same request again with the same key returns the same 201 and body, plus an
`Idempotent-Replayed: true` header, and creates nothing. The same key with a different body
returns 422.

### Retrieve

```bash
curl http://localhost:8080/transactions/32971cfb-8282-4bd0-bd66-652a0f96d15e
```

### List (optional filters)

```bash
curl http://localhost:8080/transactions
curl 'http://localhost:8080/transactions?playerId=player-1&status=PENDING'
```

### Complete or cancel

```bash
# Complete: winAmount is required (0.00 or more)
curl -X PATCH http://localhost:8080/transactions/32971cfb-8282-4bd0-bd66-652a0f96d15e \
  -H 'Content-Type: application/json' \
  -d '{"status":"COMPLETED","winAmount":12.50}'

# Cancel: winAmount must not be sent
curl -X PATCH http://localhost:8080/transactions/32971cfb-8282-4bd0-bd66-652a0f96d15e \
  -H 'Content-Type: application/json' \
  -d '{"status":"CANCELLED"}'
```

Once a transaction is COMPLETED or CANCELLED, any further PATCH returns 409:

```json
{"status":409,"title":"Transaction is not pending","currentStatus":"COMPLETED",
 "detail":"Transaction 32971cfb-... is already COMPLETED and cannot be changed",
 "instance":"/transactions/32971cfb-..."}
```
