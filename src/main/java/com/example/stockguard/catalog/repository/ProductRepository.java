package com.example.stockguard.catalog.repository;
import com.example.stockguard.catalog.dto.ProductResponse;
import com.example.stockguard.catalog.dto.ProductMetadata;
import com.example.stockguard.catalog.dto.ProductReadState;
import com.example.stockguard.catalog.entity.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    @Query("""
            select new com.example.stockguard.catalog.dto.ProductReadState(
                p.catalogVersion, i.onHandQuantity - i.reservedQuantity)
            from Product p join Inventory i on i.productId = p.id
            where p.active = true and p.id = :id
            """)
    Optional<ProductReadState> findActiveReadState(UUID id);

    @Query("""
            select new com.example.stockguard.catalog.dto.ProductMetadata(
                p.id, p.sku, p.name, p.description, p.price, p.catalogVersion)
            from Product p where p.active = true and p.id = :id
            """)
    Optional<ProductMetadata> findActiveMetadata(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForUpdate(UUID id);

    @Query(PROJECTION + " where p.id = :id")
    Optional<ProductResponse> findDetailsById(UUID id);
    String PROJECTION = """
            select new com.example.stockguard.catalog.dto.ProductResponse(
                p.id, p.sku, p.name, p.description, p.price,
                i.onHandQuantity - i.reservedQuantity)
            from Product p join Inventory i on i.productId = p.id
            """;
    @Query(value = PROJECTION + " where p.active = true order by p.createdAt desc, p.id",
           countQuery = "select count(p) from Product p join Inventory i on i.productId = p.id where p.active = true")
    Page<ProductResponse> findActive(Pageable pageable);
    @Query(PROJECTION + " where p.active = true and p.id = :id")
    Optional<ProductResponse> findActiveById(UUID id);
}
