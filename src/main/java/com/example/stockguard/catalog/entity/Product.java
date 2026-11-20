package com.example.stockguard.catalog.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {
    @Id private UUID id;
    @Column(nullable = false, length = 64) private String sku;
    @Column(nullable = false, length = 200) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal price;
    private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version
    @Column(name = "catalog_version", nullable = false) private long catalogVersion;
    protected Product() {}
    public Product(UUID id, String sku, String name, String description, BigDecimal price) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.active = true;
        this.createdAt = this.updatedAt = Instant.now();
    }
    public void update(String name, String description, BigDecimal price, Boolean active) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (price != null) this.price = price;
        if (active != null) this.active = active;
        this.updatedAt = Instant.now();
    }
}
