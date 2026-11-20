package com.example.stockguard.auth.dto;

import java.util.UUID;

public record UserResponse(UUID id, String email, String role) {}
