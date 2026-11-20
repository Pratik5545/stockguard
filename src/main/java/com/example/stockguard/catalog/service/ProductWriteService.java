package com.example.stockguard.catalog.service;

import com.example.stockguard.catalog.dto.*;
import com.example.stockguard.catalog.entity.Product;
import com.example.stockguard.catalog.repository.ProductRepository;
import com.example.stockguard.inventory.entity.Inventory;
import com.example.stockguard.inventory.repository.InventoryRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ProductWriteService {
    private final ProductRepository products;
    private final InventoryRepository inventory;

    public ProductWriteService(ProductRepository products, InventoryRepository inventory) {
        this.products = products;
        this.inventory = inventory;
    }

    public ProductResponse create(CreateProductRequest request) {
        UUID id = UUID.randomUUID();
        products.saveAndFlush(new Product(id, request.sku(), request.name().strip(),
                request.description(), request.price()));
        inventory.saveAndFlush(new Inventory(id, request.initialStock()));
        return products.findDetailsById(id).orElseThrow();
    }

    public ProductResponse update(UUID id, UpdateProductRequest request) {
        var product = products.findForUpdate(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found."));
        product.update(request.name() == null ? null : request.name().strip(),
                request.description(), request.price(), request.active());
        products.flush();
        return products.findDetailsById(id).orElseThrow();
    }
}
