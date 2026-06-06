package com.asm.delivery.service;

import com.asm.delivery.entity.AuditLog;
import com.asm.delivery.repository.AuditLogRepository;
import com.asm.delivery.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(UserPrincipal principal, String action, String targetEntity, String resourceId, Object details) {
        if (principal == null) {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserPrincipal) {
                principal = (UserPrincipal) auth.getPrincipal();
            }
        }

        String actorName = "SYSTEM";
        String actorRole = "SYSTEM";

        if (principal != null) {
            actorRole = principal.getRole() != null ? principal.getRole() : "UNKNOWN";
            actorName = principal.getDisplayName() != null ? principal.getDisplayName() : principal.getUserId();
        }

        String detailsJson;
        try {
            if (details instanceof String) {
                detailsJson = (String) details;
            } else if (details != null) {
                detailsJson = objectMapper.writeValueAsString(details);
            } else {
                detailsJson = "{}";
            }
        } catch (Exception e) {
            detailsJson = details != null ? details.toString() : "{}";
        }

        String ipAddress = "127.0.0.1";
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                ipAddress = request.getHeader("X-Forwarded-For");
                if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
                    ipAddress = request.getRemoteAddr();
                } else {
                    ipAddress = ipAddress.split(",")[0].trim();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        AuditLog log = AuditLog.builder()
                .actorName(actorName)
                .actorRole(actorRole)
                .action(action)
                .targetEntity(targetEntity)
                .resourceId(resourceId)
                .details(detailsJson)
                .ipAddress(ipAddress)
                
                .build();
        auditLogRepository.save(log);
    }

    // Overloaded method for backward compatibility
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(UserPrincipal principal, String action, String resourceId, String details) {
        String targetEntity = "UNKNOWN";
        if (action != null) {
            if (action.contains("ROUTE")) targetEntity = "ROUTE";
            else if (action.contains("DELIVERY") || action.contains("ORDER") || action.contains("STOP") || action.contains("PICKUP") || action.contains("TRANSIT") || action.contains("COMPLETE") || action.contains("FAIL") || action.contains("CANCEL") || action.contains("REPORT") || action.contains("ACCEPT")) targetEntity = "DELIVERY";
            else if (action.contains("VEHICLE")) targetEntity = "VEHICLE";
            else if (action.contains("DEPOT")) targetEntity = "DEPOT";
            else if (action.contains("ZONE")) targetEntity = "ZONE";
            else if (action.contains("SYSTEM_SETTING")) targetEntity = "SLA_SETTINGS";
        }
        
        // Wrap simple string details in JSON structure
        Object jsonDetails = details;
        if (details != null && !details.trim().startsWith("{")) {
             jsonDetails = Map.of("message", details);
        }
        
        logAction(principal, action, targetEntity, resourceId, jsonDetails);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRaw(com.asm.delivery.entity.AuditLog log) {
        auditLogRepository.save(log);
    }
}
