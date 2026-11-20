package com.example.stockguard.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Cache payload deliberately excludes inventory and active status. */
public record ProductMetadata(UUID id, String sku, String name, String description,
                              BigDecimal price, long catalogVersion) {
    public ProductResponse response(int availableQuantity) {
        return new ProductResponse(id, sku, name, description, price, availableQuantity);
    }
}
