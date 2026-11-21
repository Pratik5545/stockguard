package com.example.stockguard;

import com.example.stockguard.catalog.dto.ProductMetadata;
import com.example.stockguard.catalog.repository.ProductRepository;
import com.example.stockguard.catalog.service.ProductWriteService;
import com.example.stockguard.catalog.service.ProductService;
import com.example.stockguard.catalog.dto.UpdateProductRequest;
import com.example.stockguard.infrastructure.cache.ProductCache;
import com.example.stockguard.infrastructure.ratelimit.RedisRateLimiter;
import com.example.stockguard.infrastructure.redis.RedisFeaturesProperties;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(60)
class RedisIntegrationTests {
    private static final String SCHEMA = "redis_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String NAMESPACE = "test:" + UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    private static final class RedisHolder {
        static final GenericContainer<?> INSTANCE = start();
        private static GenericContainer<?> start() {
            var container = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no");
            container.start();
            return container;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("stockguard.redis.trust-render-edge-ip", () -> "true");
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("stockguard.jwt.secret", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("stockguard.reservation.expiry-enabled", () -> "false");
        registry.add("stockguard.redis.enabled", () -> "true");
        registry.add("stockguard.redis.namespace", () -> NAMESPACE);
        registry.add("stockguard.redis.auth-limit", () -> "3");
        registry.add("stockguard.redis.reservation-limit", () -> "2");
        registry.add("spring.data.redis.host", () -> RedisHolder.INSTANCE.getHost());
        registry.add("spring.data.redis.port", () -> RedisHolder.INSTANCE.getMappedPort(6379));
        registry.add("management.health.redis.enabled", () -> "false");
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtEncoder encoder;
    @Autowired ProductCache cache;
    @Autowired RedisRateLimiter limiter;
    @Autowired RedisFeaturesProperties settings;
    @Autowired MeterRegistry meters;
    @Autowired ProductWriteService productWrites;
    @Autowired ProductService productReads;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean ProductRepository products;
    private HttpClient client;
    private String ownerToken;
    private String otherToken;
    private String adminToken;

    @BeforeEach
    void seed() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Set<String> keys = redis.keys(NAMESPACE + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String table : List.of("idempotency_records", "order_items", "orders", "reservation_items",
                "reservations", "inventory", "products", "users")) jdbc.update("DELETE FROM " + table);
        for (UUID id : List.of(OWNER, OTHER, ADMIN)) {
            jdbc.update("INSERT INTO users(id,email,password_hash,role,created_at,updated_at) VALUES (?,?,'unused-test-hash',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, id + "@example.test", id.equals(ADMIN) ? "ADMIN" : "CUSTOMER");
        }
        jdbc.update("INSERT INTO products(id,sku,name,description,price) VALUES (?,'REDIS-001','Original','Large description',12.50)", PRODUCT);
        jdbc.update("INSERT INTO inventory(product_id,on_hand_quantity,reserved_quantity) VALUES (?,40,0)", PRODUCT);
        ownerToken = token(OWNER, "CUSTOMER");
        otherToken = token(OTHER, "CUSTOMER");
        adminToken = token(ADMIN, "ADMIN");
        clearInvocations(products);
    }

    @AfterEach void closeClient() { client.close(); }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = TestDatabase.connection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void cacheHitAvoidsMetadataQueryAndInventoryStaysFresh() throws Exception {
        assertThat(getProduct().statusCode()).isEqualTo(200);
        jdbc.update("UPDATE inventory SET reserved_quantity=7 WHERE product_id=?", PRODUCT);
        var second = getProduct();
        assertThat((Integer) JsonPath.read(second.body(), "$.availableQuantity")).isEqualTo(33);
        verify(products, times(1)).findActiveMetadata(PRODUCT);
        verify(products, times(2)).findActiveReadState(PRODUCT);
        String key = productKey(0);
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
        assertThat(redis.opsForValue().get(key)).doesNotContain("availableQuantity", "reserved", "onHand");
    }

    @Test
    void productUpdateSelectsNewVersionEvenIfOldReaderRepopulatesCache() throws Exception {
        getProduct();
        var old = new ProductMetadata(PRODUCT, "REDIS-001", "Original", "Large description", new BigDecimal("12.50"), 0);
        assertThat(request("PATCH", "/api/v1/admin/products/" + PRODUCT, adminToken, null,
                "{\"name\":\"Updated\",\"price\":15.75}", null).statusCode()).isEqualTo(200);
        cache.put(old);
        var response = getProduct();
        assertThat((String) JsonPath.read(response.body(), "$.name")).isEqualTo("Updated");
        assertThat((Double) JsonPath.read(response.body(), "$.price")).isEqualTo(15.75);
        assertThat(redis.hasKey(productKey(1))).isTrue();
        assertThat(request("PATCH", "/api/v1/admin/products/" + PRODUCT, adminToken, null,
                "{\"active\":false}", null).statusCode()).isEqualTo(200);
        assertThat(getProduct().statusCode()).isEqualTo(404);
    }

    @Test
    void rolledBackProductUpdateDoesNotInvalidateCommittedMetadata() throws Exception {
        getProduct();
        transactions.executeWithoutResult(transaction -> {
            productWrites.update(PRODUCT, new UpdateProductRequest("Uncommitted", null, new BigDecimal("99.00"), null));
            assertThat(productReads.get(PRODUCT).name()).isEqualTo("Uncommitted");
            transaction.setRollbackOnly();
        });
        assertThat(redis.hasKey(productKey(1))).isFalse();
        assertThat((String) JsonPath.read(getProduct().body(), "$.name")).isEqualTo("Original");
        assertThat(jdbc.queryForObject("SELECT catalog_version FROM products WHERE id=?", Long.class, PRODUCT)).isZero();
        productWrites.update(PRODUCT, new UpdateProductRequest("Committed", null, new BigDecimal("20.00"), null));
        assertThat((String) JsonPath.read(getProduct().body(), "$.name")).isEqualTo("Committed");
    }

    @Test
    void malformedAndExpiredCacheEntriesReloadFromPostgres() throws Exception {
        redis.opsForValue().set(productKey(0), "invalid-json", Duration.ofSeconds(60));
        assertThat(getProduct().statusCode()).isEqualTo(200);
        verify(products, times(1)).findActiveMetadata(PRODUCT);
        redis.expire(productKey(0), Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(productKey(0))));
        assertThat(getProduct().statusCode()).isEqualTo(200);
        verify(products, times(2)).findActiveMetadata(PRODUCT);
    }

    @Test
    void authenticationLimitIsSharedWithRegistrationAndIgnoresSpoofedForwardedHeaders() throws Exception {
        String login = "{\"email\":\"missing@example.test\",\"password\":\"Test-password-12345\"}";
        for (int i = 0; i < 3; i++) {
            assertThat(request("POST", "/api/v1/auth/login", null, null, login, "198.51.100." + i).statusCode()).isEqualTo(401);
        }
        var rejected = request("POST", "/api/v1/auth/login", null, null, login, "203.0.113.1");
        assertProblem(rejected, 429);
        assertThat(request("POST", "/api/v1/auth/register", null, null, login, null).statusCode()).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
    }

    @Test
    void clientsBehindTheRenderEdgeHaveSeparateAuthQuotas() throws Exception {
        String login = "{\"email\":\"missing@example.test\",\"password\":\"Test-password-12345\"}";
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(login));
        var first = builder.setHeader("CF-Connecting-IP", "2001:db8::1").build();
        for (int i = 0; i < 3; i++) {
            assertThat(client.send(first, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        var sameAddress = builder.setHeader("CF-Connecting-IP", "2001:0db8:0:0:0:0:0:1").build();
        assertProblem(client.send(sameAddress, HttpResponse.BodyHandlers.ofString()), 429);
        var other = builder.setHeader("CF-Connecting-IP", "192.0.2.12").build();
        assertThat(client.send(other, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void reservationLimitIsPerUserAndDoesNotBlockConfirmOrCancel() throws Exception {
        var first = reserve(ownerToken, "one");
        var second = reserve(ownerToken, "two");
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertProblem(reserve(ownerToken, "three"), 429);
        assertThat(reserve(otherToken, "other").statusCode()).isEqualTo(201);
        String firstId = JsonPath.read(first.body(), "$.id");
        String secondId = JsonPath.read(second.body(), "$.id");
        assertThat(request("POST", "/api/v1/reservations/" + firstId + "/cancel", ownerToken, "cancel", "{}", null).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/v1/reservations/" + secondId + "/confirm", ownerToken, "confirm", "{}", null).statusCode()).isEqualTo(201);
    }

    @Test
    void retriesRemainIdempotentAndUnauthorizedRequestsDoNotConsumeQuota() throws Exception {
        assertThat(reserve(null, "anonymous").statusCode()).isEqualTo(401);
        assertThat(reserve(adminToken, "admin").statusCode()).isEqualTo(403);
        assertThat(redis.keys(NAMESPACE + ":rate:*")).isEmpty();
        var first = reserve(ownerToken, "same");
        var replay = reserve(ownerToken, "same");
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isEqualTo(1);
        assertProblem(reserve(ownerToken, "same"), 429);
    }

    @Test
    void twoLimiterInstancesShareAnAtomicQuotaUnderConcurrentRequests() throws Exception {
        var second = new RedisRateLimiter(redis, settings, meters);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            List<Future<RedisRateLimiter.Decision>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                var selected = i % 2 == 0 ? limiter : second;
                results.add(executor.submit(() -> { gate.await(); return selected.check("concurrency", "shared", 7); }));
            }
            gate.countDown();
            int allowed = 0;
            for (var future : results) {
                var decision = future.get(15, TimeUnit.SECONDS);
                assertThat(decision.available()).isTrue();
                if (decision.retryAfterSeconds() == 0) allowed++;
            }
            assertThat(allowed).isEqualTo(7);
            String key = NAMESPACE + ":rate:concurrency:shared";
            assertThat(redis.opsForValue().get(key)).isEqualTo("7");
            assertThat(redis.getExpire(key)).isBetween(1L, 60L);
        }
    }

    @Test
    void quotaExpiresWithoutExtendingTheWindowOnRejection() {
        var shortWindow = new RedisFeaturesProperties(true, false, Duration.ofSeconds(60), Duration.ofSeconds(1), 1, 1, NAMESPACE);
        var shortLimiter = new RedisRateLimiter(redis, shortWindow, meters);
        String key = NAMESPACE + ":rate:expiry:user";
        assertThat(shortLimiter.check("expiry", "user", 1).retryAfterSeconds()).isZero();
        Long initialTtl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(shortLimiter.check("expiry", "user", 1).retryAfterSeconds()).isPositive();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(initialTtl);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertThat(shortLimiter.check("expiry", "user", 1).retryAfterSeconds()).isZero();
    }

    @Test
    void redisOutageFallsBackForCatalogAndRejectsProtectedWritesWithoutMutations() throws Exception {
        getProduct();
        var container = RedisHolder.INSTANCE;
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
        try {
            assertThat(getProduct().statusCode()).isEqualTo(200);
            assertProblem(reserve(ownerToken, "outage"), 503);
            assertProblem(request("POST", "/api/v1/auth/login", null, null,
                    "{\"email\":\"missing@example.test\",\"password\":\"Test-password-12345\"}", null), 503);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isZero();
        } finally {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
            }
        });
        assertThat(reserve(otherToken, "recovered").statusCode()).isEqualTo(201);
    }

    private String productKey(long version) { return NAMESPACE + ":product:v1:" + PRODUCT + ":" + version; }
    private HttpResponse<String> getProduct() throws Exception { return request("GET", "/api/v1/products/" + PRODUCT, null, null, null, null); }
    private HttpResponse<String> reserve(String token, String key) throws Exception {
        return request("POST", "/api/v1/reservations", token, key, "{\"productId\":\"" + PRODUCT + "\",\"quantity\":1}", null);
    }

    private HttpResponse<String> request(String method, String path, String token, String key, String body, String forwarded) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (forwarded != null) builder.header("X-Forwarded-For", forwarded);
        builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).contains("application/problem+json"));
        assertThat(Long.parseLong(response.headers().firstValue("retry-after").orElseThrow())).isPositive();
        assertThat((Integer) JsonPath.read(response.body(), "$.status")).isEqualTo(status);
    }

    private String token(UUID user, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("stockguard").subject(user.toString())
                .audience(List.of("stockguard-api")).issuedAt(now).expiresAt(now.plusSeconds(900)).claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
