package com.example.stockguard;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class DatabaseMigrationTests {

    private static final String SCHEMA = "stockguard_test_" + UUID.randomUUID().toString().replace("-", "");
    private static Connection connection;
    private static Flyway flyway;

    @BeforeAll
    static void migrateIsolatedSchema() throws SQLException {
        String url = System.getenv("TEST_DB_URL");
        String username = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");
        connection = DriverManager.getConnection(url, username, password);
        flyway = Flyway.configure().dataSource(url, username, password)
                .schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").cleanDisabled(true).load();
        flyway.migrate();
        connection.setSchema(SCHEMA);
    }

    @AfterAll
    static void removeIsolatedSchema() throws SQLException {
        if (connection != null) {
            try (Connection testConnection = connection; var statement = testConnection.createStatement()) {
                // Only this test's randomly generated schema is removed.
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    @Test
    void migrationCanBeValidatedAndRunAgainWithoutChanges() {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void availableStockIsDerivedFromValidInventory() throws SQLException {
        UUID productId = insertProduct();
        execute("INSERT INTO inventory(product_id, on_hand_quantity, reserved_quantity) VALUES ('"
                + productId + "', 10, 3)");
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT on_hand_quantity - reserved_quantity AS available "
                     + "FROM inventory WHERE product_id = '" + productId + "'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("available")).isEqualTo(7);
        }
        assertThatThrownBy(() -> execute("UPDATE inventory SET on_hand_quantity = 2 WHERE product_id = '"
                + productId + "'"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "5, -1", "2, 3"})
    void invalidStockIsRejected(int onHand, int reserved) throws SQLException {
        UUID productId = insertProduct();
        assertThatThrownBy(() -> execute("INSERT INTO inventory(product_id, on_hand_quantity, reserved_quantity) VALUES ('"
                + productId + "', " + onHand + ", " + reserved + ")"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void inventoryRequiresAnExistingProductAndOnlyOneRowPerProduct() throws SQLException {
        assertThatThrownBy(() -> execute("INSERT INTO inventory(product_id) VALUES ('" + UUID.randomUUID() + "')"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
        UUID productId = insertProduct();
        execute("INSERT INTO inventory(product_id) VALUES ('" + productId + "')");
        assertThatThrownBy(() -> execute("INSERT INTO inventory(product_id) VALUES ('" + productId + "')"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23505"));
    }

    @Test
    void duplicateSkuAndNegativePriceAreRejected() throws SQLException {
        UUID productId = insertProduct();
        assertThatThrownBy(() -> execute("INSERT INTO products(id, sku, name, price) VALUES ('"
                + UUID.randomUUID() + "', '" + productId + "', 'Keyboard', 10.00)"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23505"));
        assertThatThrownBy(() -> execute("INSERT INTO products(id, sku, name, price) VALUES ('"
                + UUID.randomUUID() + "', 'negative-price', 'Keyboard', -1.00)"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    private static UUID insertProduct() throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO products(id, sku, name, price) VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, id.toString());
            statement.setString(3, "Keyboard");
            statement.setBigDecimal(4, new java.math.BigDecimal("2500.00"));
            statement.executeUpdate();
        }
        return id;
    }

    private static void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
