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
    private final com.asm.delivery.repository.DeliveryRepository deliveryRepository;
    private final com.asm.delivery.repository.OrderRepository orderRepository;
    private final com.asm.delivery.repository.RouteRepository routeRepository;

    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
            // Prefer a human display name; never store a bare UUID/blank as the actor — fall back to the
            // role so a row reads "ADMIN" rather than machine garbage. (The real per-user identity fix is
            // the Keycloak named-account P1 in backlog.md.)
            String display = principal.getDisplayName();
            actorName = (display != null && !display.isBlank() && !UUID_PATTERN.matcher(display.trim()).matches())
                    ? display
                    : actorRole;
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

        // Enrich events with the human reference (ERP ref for deliveries/orders, R001 for routes),
        // so the audit feed shows "S00042" / "R001" instead of a raw UUID prefix. Denormalised into
        // the payload (survives, no read-time join).
        String orderRef = resolveOrderRef(targetEntity, resourceId);
        String routeName = resolveRouteName(targetEntity, resourceId);
        if (orderRef != null || routeName != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> node = detailsJson.trim().startsWith("{")
                        ? objectMapper.readValue(detailsJson, Map.class)
                        : new java.util.LinkedHashMap<>();
                if (orderRef != null) node.putIfAbsent("orderRef", orderRef);
                if (routeName != null) node.putIfAbsent("routeName", routeName);
                detailsJson = objectMapper.writeValueAsString(node);
            } catch (Exception ignored) { /* keep detailsJson as-is */ }
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

    /** Resolve the ERP reference for a DELIVERY/ORDER audit target; null when N/A or not found. */
    private String resolveOrderRef(String targetEntity, String resourceId) {
        if (resourceId == null || !("DELIVERY".equals(targetEntity) || "ORDER".equals(targetEntity))) return null;
        UUID id;
        try { id = UUID.fromString(resourceId); } catch (Exception e) { return null; }
        try {
            if ("DELIVERY".equals(targetEntity)) {
                return deliveryRepository.findById(id)
                        .map(d -> d.getOrder() != null ? d.getOrder().resolveRef() : null)
                        .orElse(null);
            }
            return orderRepository.findById(id).map(com.asm.delivery.entity.Order::resolveRef).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Resolve the generated route name (R001) for a ROUTE audit target; null when N/A or not found. */
    private String resolveRouteName(String targetEntity, String resourceId) {
        if (resourceId == null || !"ROUTE".equals(targetEntity)) return null;
        UUID id;
        try { id = UUID.fromString(resourceId); } catch (Exception e) { return null; }
        try {
            return routeRepository.findById(id).map(com.asm.delivery.entity.Route::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRaw(com.asm.delivery.entity.AuditLog log) {
        auditLogRepository.save(log);
    }
}
