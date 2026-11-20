package com.example.stockguard.inventory.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory")
public class Inventory {
    @Id @Column(name = "product_id") private UUID productId;
    @Column(name = "on_hand_quantity", nullable = false) private int onHandQuantity;
    @Column(name = "reserved_quantity", nullable = false) private int reservedQuantity;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Inventory() {}
    public int getOnHandQuantity() { return onHandQuantity; }
    public int getReservedQuantity() { return reservedQuantity; }
    public void adjust(int delta) {
        long next = (long) onHandQuantity + delta;
        if (next < reservedQuantity || next > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Adjustment would violate inventory limits.");
        }
        onHandQuantity = (int) next;
        updatedAt = Instant.now();
    }
    public Inventory(UUID productId, int onHandQuantity) {
        this.productId = productId;
        this.onHandQuantity = onHandQuantity;
        this.reservedQuantity = 0;
        this.updatedAt = Instant.now();
    }
}
