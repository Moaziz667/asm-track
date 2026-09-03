package com.asm.driver.repository;

import com.asm.driver.entity.DriverAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface DriverAuditLogRepository
        extends JpaRepository<DriverAuditLog, UUID>, JpaSpecificationExecutor<DriverAuditLog> {

    /** Rows still owed to the shared audit trail, oldest first so the replay keeps their order. */
    List<DriverAuditLog> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    long countByPublishedAtIsNull();
}
