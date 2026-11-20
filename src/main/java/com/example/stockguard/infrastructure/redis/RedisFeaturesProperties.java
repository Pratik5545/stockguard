package com.example.stockguard.infrastructure.redis;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("stockguard.redis")
public record RedisFeaturesProperties(boolean enabled, Duration cacheTtl, Duration rateWindow,
        @Min(1) @Max(10000) int authLimit, @Min(1) @Max(10000) int reservationLimit,
        @NotNull @Pattern(regexp = "[A-Za-z0-9:_-]{1,64}") String namespace) {
    public RedisFeaturesProperties {
        requireDuration(cacheTtl, Duration.ofMinutes(10), "Cache TTL");
        requireDuration(rateWindow, Duration.ofHours(1), "Rate window");
    }

    private static void requireDuration(Duration value, Duration maximum, String name) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be between 1 second and " + maximum + ".");
        }
    }
}
