package com.asm.driver.repository;

import com.asm.driver.entity.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
    Optional<Driver> findByPhone(String phone);
    boolean existsByPhone(String phone);
    List<Driver> findByActiveTrue();
}
