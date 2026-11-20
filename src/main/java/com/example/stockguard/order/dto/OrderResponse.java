package com.example.stockguard.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(UUID id, UUID reservationId, UUID userId, String status,
        UUID productId, String sku, String productName, int quantity, BigDecimal unitPrice,
        BigDecimal totalAmount, Instant createdAt) {}
