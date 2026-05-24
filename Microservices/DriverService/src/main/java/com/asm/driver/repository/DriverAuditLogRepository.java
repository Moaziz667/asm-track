package com.asm.driver.repository;

import com.asm.driver.entity.DriverAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DriverAuditLogRepository extends JpaRepository<DriverAuditLog, UUID> {
}
