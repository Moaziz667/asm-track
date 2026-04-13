package com.asm.delivery.controller;

import com.asm.delivery.service.AuditLogService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal endpoint for other microservices to push audit events.
 * Protected by the INTERNAL_SECRET header, not by JWT.
 */
@RestController
@RequestMapping("/internal/audit")
@RequiredArgsConstructor
@Hidden
public class InternalAuditController {

    private final AuditLogService auditLogService;

    @Value("${internal.secret:asm-internal-2026}")
    private String internalSecret;

    @PostMapping
    public ResponseEntity<Void> logEvent(
            @RequestHeader("X-Internal-Secret") String secret,
            @RequestParam String action,
            @RequestParam String actorName,
            @RequestParam String actorRole,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String details) {

        if (!internalSecret.equals(secret)) {
            return ResponseEntity.status(403).build();
        }

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
