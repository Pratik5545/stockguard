package com.example.stockguard.reservation.repository;

import com.example.stockguard.reservation.dto.ReservationResponse;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT r.*, i.product_id, i.sku, i.product_name, i.quantity, i.unit_price
            FROM reservations r JOIN reservation_items i ON i.reservation_id = r.id
            """;

    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean lockUser(UUID user) {
        return !jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), user).isEmpty();
    }

    public Optional<ProductSnapshot> lockProduct(UUID id) {
        return jdbc.query("SELECT id, sku, name, price, active FROM products WHERE id = ? FOR SHARE",
                (row, index) -> new ProductSnapshot(row.getObject(1, UUID.class), row.getString(2),
                        row.getString(3), row.getBigDecimal(4), row.getBoolean(5)), id).stream().findFirst();
    }

    public Stock lockStock(UUID id) {
        return jdbc.queryForObject("""
                SELECT on_hand_quantity, reserved_quantity FROM inventory WHERE product_id = ? FOR UPDATE
                """, (row, index) -> new Stock(row.getInt(1), row.getInt(2)), id);
    }

    public void writeStock(UUID product, int onHand, int reserved, Instant now) {
        int changed = jdbc.update("""
                UPDATE inventory SET on_hand_quantity = ?, reserved_quantity = ?, updated_at = ? WHERE product_id = ?
                """, onHand, reserved, Timestamp.from(now), product);
        if (changed != 1) throw new IllegalStateException("Inventory record is missing.");
    }

    public long pendingCount(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = ? AND status = 'PENDING'",
                Long.class, user);
    }

    public void insert(ReservationResponse reservation) {
        jdbc.update("""
                INSERT INTO reservations(id, user_id, status, expires_at, created_at, updated_at)
                VALUES (?, ?, 'PENDING', ?, ?, ?)
                """, reservation.id(), reservation.userId(), Timestamp.from(reservation.expiresAt()),
                Timestamp.from(reservation.createdAt()), Timestamp.from(reservation.updatedAt()));
        jdbc.update("""
                INSERT INTO reservation_items(reservation_id, product_id, sku, product_name, quantity, unit_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """, reservation.id(), reservation.productId(), reservation.sku(), reservation.productName(),
                reservation.quantity(), reservation.unitPrice());
    }

    public Optional<ReservationResponse> find(UUID id, boolean lock) {
        return jdbc.query(SELECT + " WHERE r.id = ?" + (lock ? " FOR UPDATE OF r" : ""),
                this::map, id).stream().findFirst();
    }

    public void transition(UUID id, String status, Instant now) {
        int changed = jdbc.update("""
                UPDATE reservations SET status = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'
                """, status, Timestamp.from(now), id);
        if (changed != 1) throw new IllegalStateException("Reservation transition failed.");
    }

    public List<ReservationResponse> list(UUID user, int page, int size) {
        return jdbc.query(SELECT + " WHERE r.user_id = ? ORDER BY r.created_at DESC, r.id LIMIT ? OFFSET ?",
                this::map, user, size, (long) page * size);
    }

    public long count(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = ?", Long.class, user);
    }

    public Optional<UUID> claimDue(Instant now) {
        return jdbc.query("""
                SELECT id FROM reservations WHERE status = 'PENDING' AND expires_at <= ?
                ORDER BY expires_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> row.getObject(1, UUID.class), Timestamp.from(now)).stream().findFirst();
    }

    private ReservationResponse map(ResultSet row, int index) throws SQLException {
        return new ReservationResponse(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getString("status"), row.getObject("product_id", UUID.class), row.getString("sku"),
                row.getString("product_name"), row.getInt("quantity"), row.getBigDecimal("unit_price"),
                row.getTimestamp("expires_at").toInstant(), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    public record ProductSnapshot(UUID id, String sku, String name, BigDecimal price, boolean active) {}
    public record Stock(int onHand, int reserved) {}
}
