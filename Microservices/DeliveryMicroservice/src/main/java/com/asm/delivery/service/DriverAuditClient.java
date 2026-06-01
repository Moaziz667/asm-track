package com.asm.delivery.service;

import com.asm.delivery.dto.AuditLogView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pulls driver-service audit logs over HTTP and maps them into the same
 * {@link AuditLogView} shape the rest of the admin UI consumes. The admin's
 * bearer token is forwarded so the request passes the /api/admin/** security
 * rule on the driver side.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DriverAuditClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${driver.service.url}")
    private String driverServiceUrl;

    @SuppressWarnings("unchecked")
    public List<AuditLogView> fetchDriverLogs(
            String action, String actor, String actorRole,
            LocalDateTime from, LocalDateTime to,
            int page, int size) {

        StringBuilder url = new StringBuilder(driverServiceUrl)
                .append("/api/admin/drivers/audit-logs")
                .append("?page=").append(Math.max(page, 0))
                .append("&size=").append(Math.min(Math.max(size, 1), 200));
        if (action != null && !action.isBlank())     url.append("&action=").append(action);
        if (actor != null && !actor.isBlank())       url.append("&actor=").append(actor);
        if (actorRole != null && !actorRole.isBlank()) url.append("&actorRole=").append(actorRole);
        if (from != null) url.append("&from=").append(from);
        if (to != null)   url.append("&to=").append(to);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        String bearer = currentBearer();
        if (bearer != null) headers.setBearerAuth(bearer);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url.toString(), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Collections.emptyList();
            }
            Map<String, Object> body = objectMapper.readValue(
                    response.getBody(), new TypeReference<Map<String, Object>>() {});
            Object content = body.get("content");
            if (!(content instanceof List<?> list)) {
                return Collections.emptyList();
            }
            return objectMapper.convertValue(list, new TypeReference<List<AuditLogView>>() {});
        } catch (Exception e) {
            log.warn("Failed to fetch driver audit logs from {}: {}", url, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String currentBearer() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ")) return h.substring(7);
        } catch (Exception ignored) {}
        return null;
    }
}
