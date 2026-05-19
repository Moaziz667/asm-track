package com.asm.appbackend.repository;

import com.asm.appbackend.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {
    Optional<AdminUser> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByRole(String role);
    List<AdminUser> findByCompanyId(UUID companyId);

    @Modifying
    @Query("UPDATE AdminUser u SET u.active = false WHERE u.companyId = :companyId")
    void deactivateByCompanyId(@Param("companyId") UUID companyId);
}
