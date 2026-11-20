package com.example.stockguard;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.util.Base64;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared database for one test JVM; each test suite still owns an isolated schema. */
final class TestDatabase {
    private TestDatabase() {}

    private static final class ContainerHolder {
        private static final PostgreSQLContainer INSTANCE = start();

        private static PostgreSQLContainer start() {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            var container = new PostgreSQLContainer("postgres:18")
                    .withDatabaseName("stockguard_test")
                    .withUsername("stockguard_test")
                    .withPassword(Base64.getEncoder().encodeToString(secret));
            container.start();
            // Testcontainers' resource reaper removes this container after the test JVM exits.
            return container;
        }
    }

    private static boolean external() {
        String url = System.getenv("TEST_DB_URL");
        return url != null && !url.isBlank();
    }

    static String url() {
        return external() ? System.getenv("TEST_DB_URL") : ContainerHolder.INSTANCE.getJdbcUrl();
    }

    static String username() {
        return external() ? System.getenv("TEST_DB_USERNAME") : ContainerHolder.INSTANCE.getUsername();
    }

    static String password() {
        return external() ? System.getenv().getOrDefault("TEST_DB_PASSWORD", "")
                : ContainerHolder.INSTANCE.getPassword();
    }

    static Connection connection() throws SQLException {
        return DriverManager.getConnection(url(), username(), password());
    }
}
