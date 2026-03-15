package com.asm.delivery.repository;

import com.asm.delivery.entity.DriverOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DriverOtpRepository extends JpaRepository<DriverOtp, UUID> {

    @Query("SELECT o FROM DriverOtp o WHERE o.phone = :phone AND o.used = false ORDER BY o.createdAt DESC LIMIT 1")
    Optional<DriverOtp> findLatestUnused(@Param("phone") String phone);

    @Modifying
    @Transactional
    @Query("UPDATE DriverOtp o SET o.used = true WHERE o.phone = :phone AND o.used = false")
    void invalidateAll(@Param("phone") String phone);
}
