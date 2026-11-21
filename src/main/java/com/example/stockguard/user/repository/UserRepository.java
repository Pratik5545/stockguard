package com.example.stockguard.user.repository;

import com.example.stockguard.user.entity.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmail(String email);

    @Modifying
    @Query(value = """
            INSERT INTO users (id, email, password_hash, role, created_at, updated_at)
            VALUES (:id, :email, :passwordHash, 'CUSTOMER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (email) DO NOTHING
            """, nativeQuery = true)
    void registerIfAbsent(UUID id, String email, String passwordHash);
}
