package com.example.stockguard.catalog.controller;
import com.example.stockguard.catalog.dto.*;
import com.example.stockguard.catalog.service.ProductService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirements
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService products;
    public ProductController(ProductService products) { this.products = products; }
    @GetMapping
    public ProductPage list(@RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return products.list(page, size);
    }
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) { return products.get(id); }
}
