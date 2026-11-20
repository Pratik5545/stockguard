package com.example.stockguard;

import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogIntegrationTests {
    private static final String SCHEMA = "catalog_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID ACTIVE = UUID.randomUUID();
    private static final UUID INACTIVE = UUID.randomUUID();
    @Value("${local.server.port}") int port;
    private String adminToken;
    private static final String PASSWORD = "Test-password-12345";
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwords;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("stockguard.reservation.expiry-enabled", () -> "false");
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("stockguard.jwt.secret", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void seed() throws Exception {
        adminToken = null;
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
                System.getenv("TEST_DB_USERNAME"), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
            connection.setSchema(SCHEMA);
            try (var sql = connection.createStatement()) {
                sql.executeUpdate("DELETE FROM users");
                sql.executeUpdate("DELETE FROM inventory");
                sql.executeUpdate("DELETE FROM products");
                sql.executeUpdate("INSERT INTO products(id,sku,name,price,active) VALUES ('" + ACTIVE
                        + "','ACTIVE','Visible product',100,true), ('" + INACTIVE
                        + "','INACTIVE','Hidden product',200,false)");
                sql.executeUpdate("INSERT INTO inventory(product_id,on_hand_quantity,reserved_quantity) VALUES ('"
                        + ACTIVE + "',10,3), ('" + INACTIVE + "',5,0)");
            }
        }
        assertThat(write("POST", "/api/v1/auth/register",
                "{\"email\":\"admin@example.test\",\"password\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(201);
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
                System.getenv("TEST_DB_USERNAME"), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
             var sql = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            sql.executeUpdate("UPDATE users SET role='ADMIN' WHERE email='admin@example.test'");
        }
        var login = write("POST", "/api/v1/auth/login",
                "{\"email\":\"admin@example.test\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        adminToken = JsonPath.read(login.body(), "$.accessToken");
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
                System.getenv("TEST_DB_USERNAME"), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
             var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void listAndDetailsShowOnlyActiveProductsAndDerivedAvailability() throws Exception {
        var list = get("/api/v1/products?page=0&size=1");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(list.body(), "$.totalElements")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(list.body(), "$.items[0].availableQuantity")).isEqualTo(7);
        assertThat(get("/api/v1/products/" + ACTIVE).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/products/" + INACTIVE).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/products/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat((java.util.List<?>) JsonPath.read(get("/api/v1/products?page=1&size=1").body(), "$.items")).isEmpty();
    }

    @Test
    void invalidRequestsReturnProblemResponses() throws Exception {
        for (String path : new String[]{"?page=-1", "?size=0", "?size=101", "?page=text", "/invalid-id"}) {
            var response = get("/api/v1/products" + path);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
            assertThat((Integer) JsonPath.read(response.body(), "$.status")).isEqualTo(400);
        }
    }

    private HttpResponse<String> write(String method, String path, String body) throws Exception {
        return request(method, path, body, path.startsWith("/api/v1/admin/") ? adminToken : null);
    }

    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (token != null) builder.header("Authorization", "Bearer " + token);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void registrationHashesPasswordNormalizesEmailAndCannotGrantAdmin() throws Exception {
        var registered = write("POST", "/api/v1/auth/register",
                "{\"email\":\"Customer@Example.test\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(registered.body(), "$.role")).isEqualTo("CUSTOMER");
        assertThat((String) JsonPath.read(registered.body(), "$.email")).isEqualTo("customer@example.test");
        assertThat(registered.body()).doesNotContain("password", PASSWORD);
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
                System.getenv("TEST_DB_USERNAME"), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
             var sql = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            try (var rows = sql.executeQuery("SELECT password_hash FROM users WHERE email='customer@example.test'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNotEqualTo(PASSWORD);
                assertThat(passwords.matches(PASSWORD, rows.getString(1))).isTrue();
            }
        }
        assertThat(write("POST", "/api/v1/auth/register",
                "{\"email\":\"CUSTOMER@example.test\",\"password\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(409);
        var login = write("POST", "/api/v1/auth/login",
                "{\"email\":\"CUSTOMER@example.test\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat((Integer) JsonPath.read(login.body(), "$.expiresIn")).isEqualTo(900);
        String customerToken = JsonPath.read(login.body(), "$.accessToken");
        var forbidden = request("POST", "/api/v1/admin/products", "{}", customerToken);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(request("PATCH", "/api/v1/admin/products/" + ACTIVE, "{}", customerToken).statusCode()).isEqualTo(403);
    }

    @Test
    void badCredentialsAndInvalidRegistrationAreRejected() throws Exception {
        var wrongPassword = write("POST", "/api/v1/auth/login",
                "{\"email\":\"admin@example.test\",\"password\":\"wrong-password\"}");
        var unknownAccount = write("POST", "/api/v1/auth/login",
                "{\"email\":\"unknown@example.test\",\"password\":\"wrong-password\"}");
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownAccount.statusCode()).isEqualTo(401);
        assertThat((String) JsonPath.read(wrongPassword.body(), "$.detail"))
                .isEqualTo(JsonPath.read(unknownAccount.body(), "$.detail"));
        assertThat(wrongPassword.body()).doesNotContain("wrong-password", "passwordHash");
        assertThat(write("POST", "/api/v1/auth/register", "{}").statusCode()).isEqualTo(400);
        assertThat(write("POST", "/api/v1/auth/register",
                "{\"email\":\"invalid\",\"password\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(400);
        assertThat(write("POST", "/api/v1/auth/register",
                "{\"email\":\"valid@example.test\",\"password\":\"short\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void missingMalformedTamperedExpiredAndWrongIssuerTokensAreRejected() throws Exception {
        var unauthorized = request("POST", "/api/v1/admin/products", "{}", null);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.headers().firstValue("www-authenticate")).hasValue("Bearer");
        assertThat(request("PATCH", "/api/v1/admin/products/" + ACTIVE, "{}", null).statusCode()).isEqualTo(401);
        int signatureStart = adminToken.lastIndexOf('.') + 1;
        char replacement = adminToken.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = adminToken.substring(0, signatureStart) + replacement + adminToken.substring(signatureStart + 1);
        for (String token : new String[]{"invalid", tampered, token("stockguard", "stockguard-api", -120),
                token("wrong-issuer", "stockguard-api", 900), token("stockguard", "wrong-audience", 900),
                token("stockguard", null, 900)}) {
            assertThat(request("POST", "/api/v1/admin/products", "{}", token).statusCode()).isEqualTo(401);
        }
    }

    private String token(String issuer, String audience, int expiryOffset) {
        var now = java.time.Instant.now();
        var builder = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(issuer).subject(UUID.randomUUID().toString())
                .issuedAt(now.minusSeconds(300)).expiresAt(now.plusSeconds(expiryOffset)).claim("role", "ADMIN");
        if (audience != null) builder.audience(java.util.List.of(audience));
        var claims = builder.build();
        return jwtEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void createDuplicateAndPartialUpdatePreserveInventory() throws Exception {
        String body = """
                {"sku":"NEW-SKU","name":"New product","price":125.50,"initialStock":10}
                """;
        var created = write("POST", "/api/v1/admin/products", body);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.id");
        assertThat(created.headers().firstValue("location")).hasValue("/api/v1/products/" + id);
        assertThat((Integer) JsonPath.read(created.body(), "$.availableQuantity")).isEqualTo(10);
        var duplicate = write("POST", "/api/v1/admin/products", body);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("A product with this SKU already exists.");
        var changed = write("PATCH", "/api/v1/admin/products/" + id, "{\"name\":\"Changed\",\"active\":false}");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(changed.body(), "$.name")).isEqualTo("Changed");
        assertThat((Integer) JsonPath.read(changed.body(), "$.availableQuantity")).isEqualTo(10);
        assertThat(get("/api/v1/products/" + id).statusCode()).isEqualTo(404);
        assertThat(write("PATCH", "/api/v1/admin/products/" + id, "{\"active\":true}").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/products/" + id).statusCode()).isEqualTo(200);
        assertThat(write("PATCH", "/api/v1/admin/products/" + UUID.randomUUID(), "{}").statusCode()).isEqualTo(404);
    }

    @Test
    void invalidWritesAreRejected() throws Exception {
        for (String body : new String[]{"{}", "{\"sku\":\"BAD\",\"name\":\"Product\",\"price\":-1,\"initialStock\":0}",
                "{\"sku\":\"BAD\",\"name\":\"Product\",\"price\":1.234,\"initialStock\":0}",
                "{\"sku\":\"BAD\",\"name\":\"Product\",\"price\":1,\"initialStock\":-1}"}) {
            assertThat(write("POST", "/api/v1/admin/products", body).statusCode()).isEqualTo(400);
        }
        assertThat(write("PATCH", "/api/v1/admin/products/" + ACTIVE, "{\"name\":\"   \"}").statusCode()).isEqualTo(400);
        assertThat(write("PATCH", "/api/v1/admin/products/" + ACTIVE, "{\"price\":-1}").statusCode()).isEqualTo(400);
    }

    @Test
    void inventoryFailureRollsBackProductCreation() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
                System.getenv("TEST_DB_USERNAME"), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
             var sql = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            sql.execute("ALTER TABLE inventory ADD CONSTRAINT test_reject_stock CHECK (on_hand_quantity <> 99)");
            try {
                var response = write("POST", "/api/v1/admin/products",
                        "{\"sku\":\"ROLLBACK\",\"name\":\"Rollback\",\"price\":1,\"initialStock\":99}");
                assertThat(response.statusCode()).isEqualTo(409);
                try (var rows = sql.executeQuery("SELECT count(*) FROM products WHERE sku = 'ROLLBACK'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            } finally {
                sql.execute("ALTER TABLE inventory DROP CONSTRAINT test_reject_stock");
            }
        }
    }

    private String stockPath() { return "/api/v1/admin/products/" + ACTIVE + "/stock-adjustments"; }

    @Test
    void stockAdjustmentsRespectReservedStockAndIntegerLimits() throws Exception {
        var added = write("POST", stockPath(), "{\"delta\":5}");
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(added.body(), "$.onHandQuantity")).isEqualTo(15);
        assertThat((Integer) JsonPath.read(added.body(), "$.reservedQuantity")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(added.body(), "$.availableQuantity")).isEqualTo(12);
        var removed = write("POST", stockPath(), "{\"delta\":-12}");
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(removed.body(), "$.availableQuantity")).isZero();
        assertThat(write("POST", stockPath(), "{\"delta\":-1}").statusCode()).isEqualTo(409);
        assertThat(write("POST", stockPath(), "{\"delta\":2147483647}").statusCode()).isEqualTo(409);
        assertThat(write("POST", stockPath(), "{\"delta\":-2147483648}").statusCode()).isEqualTo(409);
        assertThat((Integer) JsonPath.read(get("/api/v1/products/" + ACTIVE).body(), "$.availableQuantity")).isZero();
    }

    @Test
    void invalidStockRequestsAndUnauthorizedCallersAreRejected() throws Exception {
        for (String body : new String[]{"{}", "{\"delta\":null}", "{\"delta\":0}",
                "{\"delta\":1.5}", "{\"delta\":2147483648}"}) {
            assertThat(write("POST", stockPath(), body).statusCode()).isEqualTo(400);
        }
        assertThat(write("POST", "/api/v1/admin/products/" + UUID.randomUUID() + "/stock-adjustments",
                "{\"delta\":1}").statusCode()).isEqualTo(404);
        assertThat(request("POST", stockPath(), "{\"delta\":1}", null).statusCode()).isEqualTo(401);
        write("POST", "/api/v1/auth/register",
                "{\"email\":\"stock-customer@example.test\",\"password\":\"" + PASSWORD + "\"}");
        var login = write("POST", "/api/v1/auth/login",
                "{\"email\":\"stock-customer@example.test\",\"password\":\"" + PASSWORD + "\"}");
        String customerToken = JsonPath.read(login.body(), "$.accessToken");
        assertThat(request("POST", stockPath(), "{\"delta\":1}", customerToken).statusCode()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(get("/api/v1/products/" + ACTIVE).body(), "$.availableQuantity")).isEqualTo(7);
    }

    @Test
    void concurrentStockIncreasesDoNotLoseUpdates() throws Exception {
        assertThat(concurrentAdjustments(1)).containsOnly(200).hasSize(20);
        assertThat((Integer) JsonPath.read(get("/api/v1/products/" + ACTIVE).body(), "$.availableQuantity")).isEqualTo(27);
    }

    @Test
    void concurrentRemovalsCannotConsumeReservedStock() throws Exception {
        var statuses = concurrentAdjustments(-1);
        assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(7);
        assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(13);
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
                System.getenv("TEST_DB_USERNAME"), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
             var sql = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            try (var rows = sql.executeQuery("SELECT on_hand_quantity, reserved_quantity FROM inventory WHERE product_id='" + ACTIVE + "'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3);
                assertThat(rows.getInt(2)).isEqualTo(3);
            }
        }
    }

    private java.util.List<Integer> concurrentAdjustments(int delta) throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(20);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(20)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return write("POST", stockPath(), "{\"delta\":" + delta + "}").statusCode();
                }));
            }
            try {
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var statuses = new java.util.ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(20, java.util.concurrent.TimeUnit.SECONDS));
            return statuses;
        }
    }
}
