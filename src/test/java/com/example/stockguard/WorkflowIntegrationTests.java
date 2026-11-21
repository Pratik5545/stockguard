package com.example.stockguard;

import com.example.stockguard.reservation.config.ReservationProperties;
import com.example.stockguard.reservation.repository.ReservationRepository;
import com.example.stockguard.reservation.service.ExpiryService;
import com.example.stockguard.infrastructure.scheduler.ReservationExpiryWorker;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkflowIntegrationTests.TimeConfig.class)
@Timeout(120)
class WorkflowIntegrationTests {
    private static final String SCHEMA = "workflow_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired MutableClock clock;
    @Autowired ExpiryService expiry;
    @Autowired ReservationRepository reservations;
    @Autowired TransactionTemplate transactions;
    private HttpClient client;
    private String ownerToken;
    private String otherToken;
    private String adminToken;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("stockguard.jwt.secret", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("spring.datasource.url", () -> TestDatabase.url());
        registry.add("spring.datasource.username", () -> TestDatabase.username());
        registry.add("spring.datasource.password", () -> TestDatabase.password());
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("stockguard.reservation.expiry-enabled", () -> "false");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @BeforeEach
    void seed() {
        clock.set(START);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (String table : List.of("idempotency_records", "order_items", "orders", "reservation_items",
                "reservations", "inventory", "products", "users")) jdbc.update("DELETE FROM " + table);
        seedUser(OWNER, "CUSTOMER");
        seedUser(OTHER, "CUSTOMER");
        seedUser(ADMIN, "ADMIN");
        ownerToken = token(OWNER, "CUSTOMER");
        otherToken = token(OTHER, "CUSTOMER");
        adminToken = token(ADMIN, "ADMIN");
        jdbc.update("INSERT INTO products(id,sku,name,price) VALUES (?, 'LIMITED-001','Original product',123.45)", PRODUCT);
        jdbc.update("INSERT INTO inventory(product_id,on_hand_quantity,reserved_quantity) VALUES (?,10,0)", PRODUCT);
    }

    @AfterEach void closeClient() { client.close(); }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = databaseConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static Connection databaseConnection() throws SQLException {
        var connection = TestDatabase.connection();
        connection.setSchema(SCHEMA);
        return connection;
    }

    private void seedUser(UUID id, String role) {
        jdbc.update("""
                INSERT INTO users(id,email,password_hash,role,created_at,updated_at)
                VALUES (?,?,'unused-test-hash',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, id + "@example.test", role);
    }

    private String token(UUID user, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("stockguard").subject(user.toString())
                .audience(List.of("stockguard-api")).issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> request(String method, String path, String token, String key, String body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (key != null) builder.header("Idempotency-Key", key);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private HttpResponse<String> reserve(String token, String key, int quantity) {
        return request("POST", "/api/v1/reservations", token, key,
                "{\"productId\":\"" + PRODUCT + "\",\"quantity\":" + quantity + "}");
    }

    private HttpResponse<String> action(String id, String action, String token, String key) {
        return request("POST", "/api/v1/reservations/" + id + "/" + action, token, key, null);
    }

    private String create(int quantity) {
        var response = reserve(ownerToken, UUID.randomUUID().toString(), quantity);
        assertThat(response.statusCode()).isEqualTo(201);
        return field(response, "id");
    }

    private String field(HttpResponse<String> response, String name) {
        return JsonPath.read(response.body(), "$." + name).toString();
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private void stock(int onHand, int reserved) {
        var row = jdbc.queryForMap("SELECT on_hand_quantity,reserved_quantity FROM inventory WHERE product_id=?", PRODUCT);
        assertThat(row.get("on_hand_quantity")).isEqualTo(onHand);
        assertThat(row.get("reserved_quantity")).isEqualTo(reserved);
    }

    @Test
    void reservationReplayIsStableAndChangedPayloadConflicts() {
        var first = reserve(ownerToken, "same-key", 2);
        assertThat(first.statusCode()).isEqualTo(201);
        clock.set(START.plusSeconds(30));
        var retry = reserve(ownerToken, "same-key", 2);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(reserve(ownerToken, "same-key", 1).statusCode()).isEqualTo(409);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        stock(10, 2);
        // The same key is independent for another authenticated customer.
        assertThat(reserve(otherToken, "same-key", 1).statusCode()).isEqualTo(201);
        stock(10, 3);
    }

    @Test
    void rejectsInvalidQuantitiesKeysInactiveAndUnavailableProducts() {
        for (String quantity : List.of("0", "-1", "1.5", "3", "2147483648", "null")) {
            assertThat(request("POST", "/api/v1/reservations", ownerToken, UUID.randomUUID().toString(),
                    "{\"productId\":\"" + PRODUCT + "\",\"quantity\":" + quantity + "}").statusCode()).isEqualTo(400);
        }
        assertThat(reserve(ownerToken, null, 1).statusCode()).isEqualTo(400);
        assertThat(reserve(ownerToken, "invalid key", 1).statusCode()).isEqualTo(400);
        assertThat(reserve(null, "anonymous", 1).statusCode()).isEqualTo(401);
        assertThat(reserve(adminToken, "admin", 1).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/v1/reservations", ownerToken, "missing",
                "{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE products SET active=false WHERE id=?", PRODUCT);
        assertThat(reserve(ownerToken, "inactive", 1).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE products SET active=true WHERE id=?", PRODUCT);
        jdbc.update("UPDATE inventory SET on_hand_quantity=0 WHERE product_id=?", PRODUCT);
        assertThat(reserve(ownerToken, "unavailable", 1).statusCode()).isEqualTo(409);
        assertThat(count("reservations")).isZero();
        assertThat(count("idempotency_records")).isZero();
        stock(0, 0);
    }

    @Test
    void overduePendingHoldsCountTowardCapUntilReleased() {
        for (int i = 0; i < 3; i++) assertThat(reserve(ownerToken, "hold-" + i, 1).statusCode()).isEqualTo(201);
        assertThat(reserve(ownerToken, "fourth", 1).statusCode()).isEqualTo(409);
        clock.set(START.plusSeconds(301));
        assertThat(reserve(ownerToken, "fourth", 1).statusCode()).isEqualTo(409);
        assertThat(reserve(ownerToken, "hold-0", 1).statusCode()).isEqualTo(201);
        stock(10, 3);
        assertThat(expiry.expireNext()).isTrue();
        assertThat(reserve(ownerToken, "fourth", 1).statusCode()).isEqualTo(201);
        stock(10, 3);
    }

    @Test
    void confirmationUsesImmutableSnapshotsAndReplaysOneOrder() {
        String id = create(2);
        jdbc.update("UPDATE products SET name='Changed name',price=999,active=false WHERE id=?", PRODUCT);
        assertThat(action(id, "confirm", otherToken, "other").statusCode()).isEqualTo(404);
        assertThat(action(id, "cancel", adminToken, "admin-cancel").statusCode()).isEqualTo(404);
        assertThat(action(id, "confirm", adminToken, "admin-confirm").statusCode()).isEqualTo(404);
        var response = action(id, "confirm", ownerToken, "confirm");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(field(response, "productName")).isEqualTo("Original product");
        assertThat(new java.math.BigDecimal(field(response, "totalAmount"))).isEqualByComparingTo("246.90");
        stock(8, 0);
        var replay = action(id, "confirm", ownerToken, "confirm");
        assertThat(replay.body()).isEqualTo(response.body());
        assertThat(count("orders")).isEqualTo(1);
        assertThat(action(id, "confirm", ownerToken, "new-key").statusCode()).isEqualTo(409);
        assertThat(action(id, "cancel", ownerToken, "cancel-after-confirm").statusCode()).isEqualTo(409);
        String order = field(response, "id");
        assertThat(request("GET", "/api/v1/orders/" + order, otherToken, null, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/orders/" + order, adminToken, null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/orders/" + order, ownerToken, null, null).statusCode()).isEqualTo(200);
        assertThat(field(request("GET", "/api/v1/me/orders", ownerToken, null, null), "totalElements")).isEqualTo("1");
        assertThat(field(request("GET", "/api/v1/me/orders", otherToken, null, null), "totalElements")).isEqualTo("0");
    }

    @Test
    void cancellationReleasesStockOnlyOnceAndReadAccessIsScoped() {
        String id = create(2);
        assertThat(request("GET", "/api/v1/reservations/" + id, otherToken, null, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/reservations/" + id, adminToken, null, null).statusCode()).isEqualTo(200);
        assertThat(field(request("GET", "/api/v1/me/reservations", otherToken, null, null), "totalElements")).isEqualTo("0");
        assertThat(field(request("GET", "/api/v1/me/reservations", ownerToken, null, null), "totalElements")).isEqualTo("1");
        assertThat(request("GET", "/api/v1/me/reservations?size=101", ownerToken, null, null).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/api/v1/me/orders?page=-1", ownerToken, null, null).statusCode()).isEqualTo(400);
        var response = action(id, "cancel", ownerToken, "cancel");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(field(response, "status")).isEqualTo("CANCELLED");
        stock(10, 0);
        assertThat(action(id, "cancel", ownerToken, "cancel").body()).isEqualTo(response.body());
        assertThat(action(id, "cancel", ownerToken, "different").statusCode()).isEqualTo(409);
        assertThat(action(id, "confirm", ownerToken, "confirm-cancelled").statusCode()).isEqualTo(409);
        assertThat(count("orders")).isZero();
    }

    @Test
    void exactDeadlineRejectsActionsBeforeWorkerReleasesStock() {
        String id = create(2);
        clock.set(START.plusSeconds(300));
        assertThat(action(id, "confirm", ownerToken, "late-confirm").statusCode()).isEqualTo(409);
        assertThat(action(id, "cancel", ownerToken, "late-cancel").statusCode()).isEqualTo(409);
        stock(10, 2);
        assertThat(expiry.expireNext()).isTrue();
        stock(10, 0);
        assertThat(expiry.expireNext()).isFalse();
        assertThat(field(request("GET", "/api/v1/reservations/" + id, ownerToken, null, null), "status")).isEqualTo("EXPIRED");
        assertThat(count("orders")).isZero();
    }

    @Test
    void confirmationImmediatelyBeforeDeadlineSucceeds() {
        String id = create(1);
        clock.set(START.plusSeconds(300).minusNanos(1));
        assertThat(action(id, "confirm", ownerToken, "just-in-time").statusCode()).isEqualTo(201);
        clock.set(START.plusSeconds(301));
        assertThat(expiry.expireNext()).isFalse();
        stock(9, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7})
    void hundredCustomersCannotOversell(int available) {
        jdbc.update("UPDATE inventory SET on_hand_quantity=? WHERE product_id=?", available, PRODUCT);
        var tokens = new ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            UUID user = UUID.randomUUID();
            seedUser(user, "CUSTOMER");
            tokens.add(token(user, "CUSTOMER"));
        }
        var results = parallel(100, index -> reserve(tokens.get(index), "compete", 1).statusCode());
        assertThat(results.stream().filter(status -> status == 201).count()).isEqualTo(available);
        assertThat(results.stream().filter(status -> status == 409).count()).isEqualTo(100 - available);
        stock(available, available);
        assertThat(count("reservations")).isEqualTo(available);
        assertThat(count("idempotency_records")).isEqualTo(available);
    }

    @Test
    void concurrentDuplicateRequestsConsumeOneSlotAndOneHold() {
        var responses = parallel(20, index -> reserve(ownerToken, "duplicate", 2));
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(responses.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        stock(10, 2);
    }

    @Test
    void simultaneousRequestsCannotBypassPerUserPendingLimit() {
        var statuses = parallel(10, index -> reserve(ownerToken, "cap-" + index, 1).statusCode());
        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(7);
        stock(10, 3);
    }

    @Test
    void confirmAndCancelRaceHasOneWinner() {
        String id = create(2);
        var responses = parallel(2, index -> action(id, index == 0 ? "confirm" : "cancel", ownerToken, "race-" + index));
        long winners = responses.stream().filter(response -> response.statusCode() < 300).count();
        assertThat(winners).isEqualTo(1);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409).count()).isEqualTo(1);
        if (responses.getFirst().statusCode() == 201) {
            stock(8, 0);
            assertThat(count("orders")).isEqualTo(1);
        } else {
            stock(10, 0);
            assertThat(count("orders")).isZero();
        }
    }

    @Test
    void concurrentConfirmRetriesCreateExactlyOneOrder() {
        String id = create(1);
        var responses = parallel(10, index -> action(id, "confirm", ownerToken, "confirm-retry"));
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(responses.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
        stock(9, 0);
    }

    @Test
    void expiryRacingWithConfirmationAtDeadlineNeverCreatesOrder() {
        String id = create(1);
        clock.set(START.plusSeconds(300));
        var results = parallel(2, index -> index == 0
                ? action(id, "confirm", ownerToken, "expired-race").statusCode()
                : (expiry.expireNext() ? 200 : 204));
        assertThat(results.getFirst()).isEqualTo(409);
        // A worker may skip a row held by the confirmer; the next pass catches it.
        while (expiry.expireNext()) { }
        stock(10, 0);
        assertThat(count("orders")).isZero();
    }

    @Test
    void concurrentExpiryWorkersReleaseEachHoldOnce() {
        for (int i = 0; i < 3; i++) create(1);
        clock.set(START.plusSeconds(3600));
        var results = parallel(10, index -> expiry.expireNext());
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
        stock(10, 0);
        assertThat(expiry.expireNext()).isFalse();
    }

    @Test
    void workerBatchLimitAndRecreatedServiceCatchUpOverdueRows() {
        for (int i = 0; i < 3; i++) create(1);
        clock.set(START.plusSeconds(3600));
        var metrics = new SimpleMeterRegistry();
        var worker = new ReservationExpiryWorker(expiry,
                new ReservationProperties(Duration.ofMinutes(5), 2, 3, 2), metrics);
        worker.runBatch();
        stock(10, 1);
        // No in-memory timer state is required: a fresh service can process persisted overdue rows.
        var recreated = new ExpiryService(reservations, clock);
        Boolean caughtUp = transactions.execute(status -> recreated.expireNext());
        assertThat(caughtUp).isTrue();
        stock(10, 0);
        assertThat(metrics.counter("stockguard.reservations.expired").count()).isEqualTo(2);
    }

    @Test
    void expirySkipsLockedReservationAndProcessesAnother() throws Exception {
        String first = create(1);
        create(1);
        clock.set(START.plusSeconds(301));
        try (var connection = databaseConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM reservations WHERE id=? FOR UPDATE")) {
                statement.setObject(1, UUID.fromString(first));
                statement.executeQuery().close();
                assertThat(expiry.expireNext()).isTrue();
                assertThat(expiry.expireNext()).isFalse();
                stock(10, 1);
            } finally { connection.rollback(); }
        }
        assertThat(expiry.expireNext()).isTrue();
        stock(10, 0);
    }

    @Test
    void confirmationRechecksDeadlineAfterWaitingForInventoryLock() throws Exception {
        String id = create(1);
        try (var connection = databaseConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT product_id FROM inventory WHERE product_id=? FOR UPDATE")) {
                statement.setObject(1, PRODUCT);
                statement.executeQuery().close();
                var response = executor.submit(() -> action(id, "confirm", ownerToken, "waited"));
                try {
                    await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE pid <> pg_backend_pid() AND datname = current_database()
                              AND wait_event_type = 'Lock' AND query LIKE '%reserved_quantity FROM inventory%'
                            """, Long.class) > 0);
                    clock.set(START.plusSeconds(300));
                } finally { connection.rollback(); }
                assertThat(response.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
                stock(10, 1);
                assertThat(expiry.expireNext()).isTrue();
            } finally { connection.rollback(); }
        }
    }

    @Test
    void reservationInsertFailureRollsBackStockAndIdempotency() {
        jdbc.execute("ALTER TABLE reservation_items ADD CONSTRAINT test_item_failure CHECK (quantity <> 1)");
        try {
            assertThat(reserve(ownerToken, "retry-failed", 1).statusCode()).isEqualTo(409);
            stock(10, 0);
            assertThat(count("reservations")).isZero();
            assertThat(count("idempotency_records")).isZero();
        } finally { jdbc.execute("ALTER TABLE reservation_items DROP CONSTRAINT test_item_failure"); }
        assertThat(reserve(ownerToken, "retry-failed", 1).statusCode()).isEqualTo(201);
    }

    @Test
    void orderItemFailureRollsBackEntireConfirmation() {
        String id = create(2);
        jdbc.execute("ALTER TABLE order_items ADD CONSTRAINT test_order_failure CHECK (quantity <> 2)");
        try {
            assertThat(action(id, "confirm", ownerToken, "order-retry").statusCode()).isEqualTo(409);
            stock(10, 2);
            assertThat(count("orders")).isZero();
            assertThat(count("idempotency_records")).isEqualTo(1);
            assertThat(field(request("GET", "/api/v1/reservations/" + id, ownerToken, null, null), "status")).isEqualTo("PENDING");
        } finally { jdbc.execute("ALTER TABLE order_items DROP CONSTRAINT test_order_failure"); }
        assertThat(action(id, "confirm", ownerToken, "order-retry").statusCode()).isEqualTo(201);
        stock(8, 0);
    }

    @Test
    void transitionFailureRollsBackCancellationAndExpiry() {
        String id = create(1);
        jdbc.execute("ALTER TABLE reservations ADD CONSTRAINT test_transition_failure CHECK (status='PENDING')");
        try {
            assertThat(action(id, "cancel", ownerToken, "failed-cancel").statusCode()).isEqualTo(409);
            stock(10, 1);
            clock.set(START.plusSeconds(301));
            assertThatThrownBy(() -> expiry.expireNext()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            stock(10, 1);
        } finally { jdbc.execute("ALTER TABLE reservations DROP CONSTRAINT test_transition_failure"); }
        assertThat(expiry.expireNext()).isTrue();
        stock(10, 0);
    }

    @Test
    void stockAdjustmentRacingReservationPreservesInvariant() {
        jdbc.update("UPDATE inventory SET on_hand_quantity=5 WHERE product_id=?", PRODUCT);
        var results = parallel(2, index -> index == 0
                ? reserve(ownerToken, "mixed", 2).statusCode()
                : request("POST", "/api/v1/admin/products/" + PRODUCT + "/stock-adjustments",
                        adminToken, null, "{\"delta\":-5}").statusCode());
        if (results.getFirst() == 201) {
            assertThat(results.get(1)).isEqualTo(409);
            stock(5, 2);
        } else {
            assertThat(results.getFirst()).isEqualTo(409);
            assertThat(results.get(1)).isEqualTo(200);
            stock(0, 0);
        }
    }

    @Test
    void swaggerAndOpenApiAreAvailableWithoutLogin() {
        var spec = request("GET", "/v3/api-docs", null, null, null);
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body()).contains("/api/v1/reservations", "/api/v1/me/orders", "bearerAuth", "Idempotency-Key");
        assertThat((String) JsonPath.read(spec.body(), "$.servers[0].url")).isEqualTo("/");
        assertThat((Map<?, ?>) JsonPath.read(spec.body(), "$.paths['/api/v1/auth/register'].post.responses"))
                .containsKey("201");
        assertThat(request("GET", "/swagger-ui/index.html", null, null, null).statusCode()).isEqualTo(200);
    }

    private <T> List<T> parallel(int tasks, IntFunction<T> action) {
        var ready = new CountDownLatch(tasks);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out.");
                    return action.apply(index);
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(45, TimeUnit.SECONDS));
            return results;
        } catch (Exception exception) { throw new RuntimeException(exception); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(START);
        void set(Instant value) { instant.set(value); }
        @Override public Instant instant() { return instant.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
