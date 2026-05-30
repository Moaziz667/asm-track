package com.asm.delivery.controller;

import com.asm.delivery.service.AuditLogService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal endpoint for other microservices to push audit events.
 * Protected by OAuth2 service token (role=SERVICE) validated by JwtAuthFilter + SecurityConfig.
 */
@RestController
@RequestMapping("/internal/audit")
@RequiredArgsConstructor
@Hidden
public class InternalAuditController {

    private final AuditLogService auditLogService;

    @PostMapping
    public ResponseEntity<Void> logEvent(
            @RequestParam String action,
            @RequestParam String actorName,
            @RequestParam String actorRole,
            String resourceId,
            String details) {

        // Build a synthetic principal-less log with explicit actor info
        com.asm.delivery.entity.AuditLog log = com.asm.delivery.entity.AuditLog.builder()
                .actorName(actorName)
                .actorRole(actorRole)
                .action(action)
                .targetEntity("ADMIN_USER")
                .resourceId(resourceId != null ? resourceId : "")
                .details(details != null ? details : "{}")
                .ipAddress("internal")
                .build();

        // Persist directly via repository injected through the service's own transaction
        auditLogService.logRaw(log);
        return ResponseEntity.ok().build();
    }
}
