package com.example.stockguard.inventory.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record StockAdjustmentRequest(
        @NotNull @Digits(integer = 10, fraction = 0)
        @DecimalMin("-2147483648") @DecimalMax("2147483647") BigDecimal delta) {}
