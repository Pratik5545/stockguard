package com.example.stockguard.catalog.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String sku,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 10000) String description,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @PositiveOrZero Integer initialStock) {}
