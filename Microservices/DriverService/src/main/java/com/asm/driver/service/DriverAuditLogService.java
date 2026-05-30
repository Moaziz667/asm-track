package com.asm.driver.service;

import com.asm.driver.entity.DriverAuditLog;
import com.asm.driver.repository.DriverAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DriverAuditLogService {

    private final DriverAuditLogRepository auditLogRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, UUID resourceId, String actorName, String actorRole, String details) {
        auditLogRepo.save(DriverAuditLog.builder()
                .action(action)
                .resourceId(resourceId)
                
                .actorName(actorName)
                .actorRole(actorRole)
                .details(details)
                .build());
    }
}
