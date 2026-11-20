package com.example.stockguard.reservation.dto;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record CreateReservationRequest(@NotNull UUID productId,
        @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
        BigDecimal quantity) {}
