package com.asm.driver.repository;

import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverOnlineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.asm.driver.entity.DriverAccountStatus;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
    Optional<Driver> findByPhone(String phone);
    Optional<Driver> findByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByEmail(String email);
    List<Driver> findByAccountStatus(DriverAccountStatus accountStatus);
    List<Driver> findByAccountStatusAndOnlineStatus(DriverAccountStatus accountStatus, DriverOnlineStatus onlineStatus);
    List<Driver> findByOnlineStatusNotAndLastLocationAtBefore(DriverOnlineStatus status, LocalDateTime threshold);

    /** Single UPDATE to set all stale online/on-duty drivers to OFFLINE — avoids N+1 saves. */
    @Modifying
    @Query("UPDATE Driver d SET d.onlineStatus = :offline, d.updatedAt = :now " +
           "WHERE d.onlineStatus <> :offline AND d.lastLocationAt < :threshold")
    int bulkOfflineStaleDrivers(
            @Param("offline") DriverOnlineStatus offline,
            @Param("now") LocalDateTime now,
            @Param("threshold") LocalDateTime threshold);
}
