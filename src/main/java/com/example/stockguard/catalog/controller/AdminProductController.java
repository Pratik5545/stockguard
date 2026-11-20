package com.example.stockguard.catalog.controller;

import com.example.stockguard.catalog.dto.*;
import com.example.stockguard.catalog.service.ProductWriteService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products")
public class AdminProductController {
    private final ProductWriteService products;

    public AdminProductController(ProductWriteService products) {
        this.products = products;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        var response = products.create(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + response.id())).body(response);
    }

    @PatchMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return products.update(id, request);
    }
}
