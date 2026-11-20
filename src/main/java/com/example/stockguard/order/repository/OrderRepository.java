package com.example.stockguard.order.repository;

import com.example.stockguard.order.dto.OrderResponse;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT o.*, i.product_id, i.sku, i.product_name, i.quantity, i.unit_price
            FROM orders o JOIN order_items i ON i.order_id = o.id
            """;

    public OrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(OrderResponse order) {
        jdbc.update("""
                INSERT INTO orders(id, reservation_id, user_id, status, total_amount, created_at)
                VALUES (?, ?, ?, 'PLACED', ?, ?)
                """, order.id(), order.reservationId(), order.userId(), order.totalAmount(), Timestamp.from(order.createdAt()));
        jdbc.update("""
                INSERT INTO order_items(order_id, product_id, sku, product_name, quantity, unit_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """, order.id(), order.productId(), order.sku(), order.productName(), order.quantity(), order.unitPrice());
    }

    public Optional<OrderResponse> find(UUID id) {
        return jdbc.query(SELECT + " WHERE o.id = ?", this::map, id).stream().findFirst();
    }

    public List<OrderResponse> list(UUID user, int page, int size) {
        return jdbc.query(SELECT + " WHERE o.user_id = ? ORDER BY o.created_at DESC, o.id LIMIT ? OFFSET ?",
                this::map, user, size, (long) page * size);
    }

    public long count(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Long.class, user);
    }

    private OrderResponse map(ResultSet row, int index) throws SQLException {
        return new OrderResponse(row.getObject("id", UUID.class), row.getObject("reservation_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getString("status"), row.getObject("product_id", UUID.class),
                row.getString("sku"), row.getString("product_name"), row.getInt("quantity"),
                row.getBigDecimal("unit_price"), row.getBigDecimal("total_amount"), row.getTimestamp("created_at").toInstant());
    }
}
