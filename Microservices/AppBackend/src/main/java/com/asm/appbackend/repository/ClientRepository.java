package com.asm.appbackend.repository;

import com.asm.appbackend.entity.Client;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<Client, UUID> {
    Optional<Client> findByPhone(String phone);

    @Query("SELECT c FROM Client c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) OR c.phone LIKE CONCAT('%', :search, '%') ORDER BY c.createdAt DESC")
    Page<Client> searchByNameOrPhone(@Param("search") String search, Pageable pageable);

    @Query("SELECT c FROM Client c ORDER BY c.createdAt DESC")
    Page<Client> findAllOrderByCreatedAtDesc(Pageable pageable);
}
