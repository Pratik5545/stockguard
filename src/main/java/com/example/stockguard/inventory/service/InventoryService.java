package com.example.stockguard.inventory.service;

import com.example.stockguard.inventory.dto.*;
import com.example.stockguard.inventory.repository.InventoryRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryService {
    private final InventoryRepository inventory;

    public InventoryService(InventoryRepository inventory) { this.inventory = inventory; }

    @Transactional
    public InventoryResponse adjust(UUID productId, StockAdjustmentRequest request) {
        int delta = request.delta().intValueExact();
        if (delta == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Delta must be a nonzero integer.");
        }
        var stock = inventory.findForUpdate(productId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Product inventory not found."));
        try {
            stock.adjust(delta);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "On-hand quantity must remain between reserved quantity and 2147483647.");
        }
        inventory.flush();
        return new InventoryResponse(productId, stock.getOnHandQuantity(), stock.getReservedQuantity(),
                stock.getOnHandQuantity() - stock.getReservedQuantity());
    }
}
