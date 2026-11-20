package com.example.stockguard.reservation.config;

import java.time.Duration;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "stockguard.reservation")
public record ReservationProperties(Duration holdDuration, @Min(1) @Max(100) int maxQuantity,
                                    @Min(1) @Max(100) int maxPending,
                                    @Min(1) @Max(1000) int expiryBatchSize) {
    public ReservationProperties {
        if (holdDuration == null || holdDuration.compareTo(Duration.ofSeconds(1)) < 0
                || holdDuration.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Reservation hold duration must be between 1 second and 15 minutes.");
        }
    }
}
