package com.example.stockguard.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 128) String password) {
    @Override public String toString() { return "RegisterRequest[redacted]"; }
}
