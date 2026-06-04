package com.asm.driver.repository;

import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverOnlineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
