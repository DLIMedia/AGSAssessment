# AGS Transactions Service

Spring Boot service for recording casino wagers (PENDING → COMPLETED / CANCELLED) with
idempotent creation and safe concurrent status changes.

## Requirements

- JDK 17 or newer (no Maven install needed; the Maven wrapper is included)

## Build, test, run

```bash
./mvnw test             # run the test suite
./mvnw spring-boot:run  # start on http://localhost:8080
```

_Endpoint docs and example curl commands: TODO._
