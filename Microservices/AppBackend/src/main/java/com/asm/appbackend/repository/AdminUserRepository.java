package com.asm.appbackend.repository;

import com.asm.appbackend.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {
    Optional<AdminUser> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByRole(String role);

    /** Rows that may diverge from Keycloak — the reconciler's fast incremental pass. */
    List<AdminUser> findByKcSyncedFalse();
}

