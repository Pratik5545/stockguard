package com.example.stockguard.reservation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(UUID id, UUID userId, String status, UUID productId,
        String sku, String productName, int quantity, BigDecimal unitPrice,
        Instant expiresAt, Instant createdAt, Instant updatedAt) {}
