package com.example.stockguard.catalog.service;
import com.example.stockguard.catalog.dto.*;
import com.example.stockguard.catalog.repository.ProductRepository;
import com.example.stockguard.infrastructure.cache.ProductCache;
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
    private final ProductCache cache;
    public ProductService(ProductRepository products, ProductCache cache) {
        this.products = products;
        this.cache = cache;
    }
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
        if (!cache.enabled()) return products.findActiveById(id).orElseThrow(ProductService::notFound);
        var state = products.findActiveReadState(id).orElseThrow(ProductService::notFound);
        var metadata = cache.get(id, state.catalogVersion()).orElseGet(() -> {
            var loaded = products.findActiveMetadata(id).orElseThrow(ProductService::notFound);
            cache.put(loaded);
            return loaded;
        });
        return metadata.response(state.availableQuantity());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found.");
    }
}
