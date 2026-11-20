package com.example.stockguard.inventory.dto;

import java.util.UUID;

public record InventoryResponse(UUID productId, int onHandQuantity,
                                int reservedQuantity, int availableQuantity) {}
