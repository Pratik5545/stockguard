package com.example.stockguard.inventory.controller;

import com.example.stockguard.inventory.dto.*;
import com.example.stockguard.inventory.service.InventoryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) { this.inventory = inventory; }

    @PostMapping("/{id}/stock-adjustments")
    public InventoryResponse adjust(@PathVariable UUID id, @Valid @RequestBody StockAdjustmentRequest request) {
        return inventory.adjust(id, request);
    }
}
