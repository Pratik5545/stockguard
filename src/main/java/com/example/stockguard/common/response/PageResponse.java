package com.example.stockguard.common.response;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalElements, long totalPages) {
    public static void validate(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Page must be nonnegative and size between 1 and 100.");
        }
    }
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total, (total + size - 1) / size);
    }
}
