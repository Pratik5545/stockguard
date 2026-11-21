package com.example.stockguard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "stockguard.reservation.expiry-enabled=false",
        "stockguard.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class StockGuardApplicationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.stockguard.user.repository.UserRepository users;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.stockguard.inventory.repository.InventoryRepository inventory;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.stockguard.catalog.repository.ProductRepository products;

    @Value("${local.server.port}")
    private int port;

    @Test
    void frontendIsPublicButApiPermissionsRemainEnforced() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/", "/index.html", "/assets/styles.css", "/assets/app.js", "/assets/api.js"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(response.body()).isNotBlank();
                if (path.equals("/")) assertThat(response.body()).contains("StockGuard", "Content-Security-Policy");
            }
            for (String path : new String[]{"/api/v1/me/reservations", "/api/v1/me/orders", "/api/v1/admin/products"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(401);
            }
        }
    }

    @Test
    void applicationStartsAndReportsHealthyOverHttp() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(
                    contentType -> assertThat(contentType).contains("json"));
            String status = JsonPath.read(response.body(), "$.status");
            assertThat(status).isEqualTo("UP");
            assertThat(response.body()).doesNotContain("\"components\"", "\"details\"");
        }
    }
}
