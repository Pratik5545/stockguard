package com.example.stockguard.catalog.service;
import com.example.stockguard.catalog.dto.*;
import com.example.stockguard.catalog.repository.ProductRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository products;
    public ProductService(ProductRepository products) { this.products = products; }
    public ProductPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Page must be nonnegative and size must be between 1 and 100.");
        }
        var result = products.findActive(PageRequest.of(page, size));
        return new ProductPage(result.getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }
    public ProductResponse get(UUID id) {
        return products.findActiveById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found."));
    }
}
