package com.example.stockguard.catalog.dto;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(UUID id, String sku, String name, String description,
                              BigDecimal price, int availableQuantity) {}
