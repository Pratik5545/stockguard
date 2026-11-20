package com.example.stockguard.catalog.dto;
import java.util.List;

public record ProductPage(List<ProductResponse> items, int page, int size,
                          long totalElements, int totalPages) {}
