package com.example.stockguard.catalog.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

// Null or omitted fields remain unchanged; an empty description clears its text.
public record UpdateProductRequest(
        @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*") String name,
        @Size(max = 10000) String description,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        Boolean active) {}
