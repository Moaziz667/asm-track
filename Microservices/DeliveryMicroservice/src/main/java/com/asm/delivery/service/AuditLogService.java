package com.asm.delivery.service;

import com.asm.delivery.entity.AuditLog;
import com.asm.delivery.repository.AuditLogRepository;
import com.asm.delivery.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(UserPrincipal principal, String action, String resourceId, String details) {
        AuditLog log = AuditLog.builder()
                .actorName(principal != null ? principal.getName() : "SYSTEM")
                .actorRole(principal != null ? principal.getAuthorities().toString() : "SYSTEM")
                .action(action)
                .resourceId(resourceId)
                .details(details)
                .ipAddress("127.0.0.1") // Can be updated to fetch real IP from request context
                .build();
        auditLogRepository.save(log);
    }
}
