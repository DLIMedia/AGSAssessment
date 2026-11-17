package com.ags.transactions.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ags.transactions.Concurrently;
import com.ags.transactions.Concurrently.Outcome;
import com.ags.transactions.idempotency.IdempotencyRecordRepository;
import com.ags.transactions.transaction.TransactionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end tests over real HTTP against a running server and the real H2 database.
 *
 * <p>A real port (not MockMvc) so that concurrent requests go through Tomcat's own worker
 * threads, exactly as two clients would. Requests are built from raw JSON strings so the tests
 * pin the wire format, not just the Java types.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionApiTest {

    private static final String BET = """
            {"roundId": "round-1", "playerId": "player-1", "gameName": "Buffalo Gold",
             "betAmount": 5.00, "currency": "USD"}""";
    private static final String COMPLETE = """
            {"status": "COMPLETED", "winAmount": 12.50}""";
    private static final String CANCEL = """
            {"status": "CANCELLED"}""";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    IdempotencyRecordRepository idempotencyRecords;

    @AfterEach
    void cleanDatabase() {
        idempotencyRecords.deleteAll();
        transactions.deleteAll();
    }

    // ---- POST + idempotency ----

    @Test
    void createReturns201WithLocationAndPendingTransaction() throws Exception {
        Response response = post(newKey(), BET);

        assertThat(response.status()).isEqualTo(201);
        String id = response.body().get("transactionId").asString();
        assertThat(response.header("Location")).isEqualTo("/transactions/" + id);
        assertThat(response.header("Idempotent-Replayed")).isNull();
        assertThat(response.body().get("status").asString()).isEqualTo("PENDING");
        assertThat(response.body().get("betAmount").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(response.body().get("winAmount").isNull()).isTrue();
        assertThat(response.body().get("createdAt").asString()).isNotBlank();
    }

    @Test
    void repeatedPostWithSameKeyReturnsOriginalResponseAndCreatesNothing() throws Exception {
        String key = newKey();
        Response first = post(key, BET);

        Response second = post(key, BET);

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void concurrentPostsWithSameKeyCreateExactlyOneTransaction() throws Exception {
        String key = newKey();
        List<Callable<Response>> requests = Collections.nCopies(8, () -> post(key, BET));

        List<Outcome<Response>> outcomes = Concurrently.run(requests);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        List<Response> responses = outcomes.stream().map(Outcome::value).toList();
        // Every caller gets the original 201 (duplicates wait, they don't fail) ...
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(responses).extracting(r -> r.body().get("transactionId").asString()).containsOnly(
                responses.get(0).body().get("transactionId").asString());
        // ... exactly one of them actually created it ...
        assertThat(responses).filteredOn(r -> r.header("Idempotent-Replayed") == null).hasSize(1);
        // ... and the database agrees.
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyReturns422() throws Exception {
        String key = newKey();
        post(key, BET);

        Response response = post(key, BET.replace("5.00", "6.00"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        Response response = send(HttpRequest.newBuilder(uri("/transactions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BET)));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("detail").asString()).contains("Idempotency-Key");
        assertThat(transactions.count()).isZero();
    }

    @Test
    void overLongIdempotencyKeyReturns400() throws Exception {
        assertThat(post("k".repeat(256), BET).status()).isEqualTo(400);
        assertThat(post("k".repeat(255), BET).status()).isEqualTo(201);
    }

    // ---- validation ----

    @ParameterizedTest(name = "{0} = {1} is rejected")
    @CsvSource(delimiter = '|', textBlock = """
            betAmount | 0
            betAmount | -1.00
            betAmount | 1.001
            betAmount | null
            currency  | "usd"
            currency  | "ABC"
            currency  | null
            roundId   | ""
            playerId  | null
            gameName  | "   "
            """)
    void invalidCreateBodyReturns400WithTheField(String field, String jsonValue) throws Exception {
        JsonNode body = json.readTree(BET);
        ((tools.jackson.databind.node.ObjectNode) body).set(field, json.readTree(jsonValue));

        Response response = post(newKey(), json.writeValueAsString(body));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body().get("errors").findValuesAsString("field")).containsExactly(field);
        assertThat(transactions.count()).isZero();
        assertThat(idempotencyRecords.count()).isZero();
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        assertThat(post(newKey(), "{not json").status()).isEqualTo(400);
    }

    // ---- GET ----

    @Test
    void getReturnsTheTransaction() throws Exception {
        Response created = post(newKey(), BET);
        String id = created.body().get("transactionId").asString();

        Response response = get("/transactions/" + id);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(created.body());
    }

    @Test
    void getUnknownIdReturns404AndNonUuidReturns400() throws Exception {
        Response unknown = get("/transactions/" + UUID.randomUUID());
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.contentType()).startsWith("application/problem+json");

        assertThat(get("/transactions/not-a-uuid").status()).isEqualTo(400);
    }

    @Test
    void listFiltersByPlayerAndStatus() throws Exception {
        String p1Pending = id(post(newKey(), BET));
        String p1Completed = id(post(newKey(), BET));
        patch(p1Completed, COMPLETE);
        id(post(newKey(), BET.replace("player-1", "player-2")));

        assertThat(get("/transactions").body()).hasSize(3);
        assertThat(ids(get("/transactions?playerId=player-1"))).containsExactly(p1Pending, p1Completed);
        assertThat(ids(get("/transactions?playerId=player-1&status=COMPLETED"))).containsExactly(p1Completed);
        assertThat(get("/transactions?status=NOPE").status()).isEqualTo(400);
    }

    // ---- PATCH ----

    @Test
    void completeSetsWinAmount() throws Exception {
        String id = id(post(newKey(), BET));

        Response response = patch(id, COMPLETE);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(response.body().get("winAmount").decimalValue()).isEqualByComparingTo("12.50");
    }

    @Test
    void cancelLeavesWinAmountNull() throws Exception {
        String id = id(post(newKey(), BET));

        Response response = patch(id, CANCEL);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(response.body().get("winAmount").isNull()).isTrue();
    }

    @ParameterizedTest(name = "{0} then {1} returns 409")
    @CsvSource(delimiter = '|', textBlock = """
            {"status": "COMPLETED", "winAmount": 12.50} | {"status": "CANCELLED"}
            {"status": "CANCELLED"}                     | {"status": "COMPLETED", "winAmount": 1}
            {"status": "COMPLETED", "winAmount": 12.50} | {"status": "COMPLETED", "winAmount": 12.50}
            """)
    void finalTransactionCannotChange(String first, String second) throws Exception {
        String id = id(post(newKey(), BET));
        Response settled = patch(id, first);

        Response response = patch(id, second);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("currentStatus").asString())
                .isEqualTo(settled.body().get("status").asString());
        assertThat(get("/transactions/" + id).body()).isEqualTo(settled.body());
    }

    @ParameterizedTest(name = "{0} is rejected")
    @CsvSource(delimiter = '|', textBlock = """
            {"status": "COMPLETED"}
            {"status": "COMPLETED", "winAmount": -1}
            {"status": "COMPLETED", "winAmount": 1.001}
            {"status": "CANCELLED", "winAmount": 0}
            {"status": "PENDING"}
            {"status": "NOPE"}
            {}
            """)
    void invalidSettlementReturns400AndChangesNothing(String body) throws Exception {
        String id = id(post(newKey(), BET));

        Response response = patch(id, body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(get("/transactions/" + id).body().get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void patchUnknownIdReturns404() throws Exception {
        assertThat(patch(UUID.randomUUID().toString(), CANCEL).status()).isEqualTo(404);
    }

    /** Repeated because a single round could pass by luck if the requests didn't overlap. */
    @Test
    void concurrentCompleteAndCancelHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 20; round++) {
            String id = id(post(newKey(), BET));

            List<Outcome<Response>> outcomes = Concurrently.run(
                    () -> patch(id, COMPLETE),
                    () -> patch(id, CANCEL));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            List<Response> responses = outcomes.stream().map(Outcome::value).toList();
            assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            Response winner = responses.stream().filter(r -> r.status() == 200).findFirst().orElseThrow();
            Response loser = responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();

            // The loser is told what actually happened, and the stored row is the winner's.
            assertThat(loser.body().get("currentStatus").asString())
                    .isEqualTo(winner.body().get("status").asString());
            assertThat(get("/transactions/" + id).body()).isEqualTo(winner.body());
        }
    }

    // ---- helpers ----

    record Response(int status, HttpResponse<String> raw, JsonNode body) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        String contentType() {
            return header("Content-Type");
        }
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String id(Response response) {
        assertThat(response.status()).isEqualTo(201);
        return response.body().get("transactionId").asString();
    }

    private static List<String> ids(Response response) {
        return IntStream.range(0, response.body().size())
                .mapToObj(i -> response.body().get(i).get("transactionId").asString())
                .toList();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Response post(String key, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/transactions"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private Response patch(String id, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/transactions/" + id))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
    }

    private Response get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> raw = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = raw.body().isEmpty() ? null : json.readTree(raw.body());
        return new Response(raw.statusCode(), raw, body);
    }
}
