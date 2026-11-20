package com.example.stockguard.inventory.repository;

import com.example.stockguard.inventory.entity.Inventory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from Inventory i where i.productId = :id")
    java.util.Optional<Inventory> findForUpdate(UUID id);
}
