package com.example.stockguard.idempotency;

public record IdempotentResult<T>(T body, int status, boolean replayed) {}
