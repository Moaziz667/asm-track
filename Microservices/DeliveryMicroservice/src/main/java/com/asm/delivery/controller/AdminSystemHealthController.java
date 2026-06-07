package com.asm.delivery.controller;

import com.asm.delivery.service.DlqReplayService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator-facing system health: dead-letter queue depths, Resilience4j circuit-breaker
 * states, and a derived ERP connectivity signal. Backs the admin "System Health" page.
 */
@RestController
@RequestMapping("/api/admin/system")
@Tag(name = "Admin System Health", description = "DLQ, circuit breakers and ERP connectivity")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminSystemHealthController {

    private final DlqReplayService dlqReplayService;
    // Optional: the registry only exists once a circuit breaker has been created.
    private final ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistry;

    @GetMapping("/health")
    @Operation(summary = "Aggregated system health for the operator console")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 1. DLQ depths
        Map<String, Object> dlq = dlqReplayService.depths();
        out.put("dlq", dlq);

        // 2. Circuit breakers
        List<Map<String, Object>> breakers = new ArrayList<>();
        CircuitBreakerRegistry registry = circuitBreakerRegistry.getIfAvailable();
        if (registry != null) {
            for (CircuitBreaker cb : registry.getAllCircuitBreakers()) {
                CircuitBreaker.Metrics m = cb.getMetrics();
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("name", cb.getName());
                b.put("state", cb.getState().name());
                b.put("failureRate", m.getFailureRate());
                b.put("bufferedCalls", m.getNumberOfBufferedCalls());
                b.put("failedCalls", m.getNumberOfFailedCalls());
                b.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
                breakers.add(b);
            }
        }
        out.put("circuitBreakers", breakers);

        // 3. Derived ERP signal: reachable unless an ERP-related breaker is OPEN.
        boolean erpDegraded = breakers.stream().anyMatch(b ->
                String.valueOf(b.get("name")).toLowerCase().contains("erp")
                        && "OPEN".equals(b.get("state")));
        long erpDlqDepth = dlq.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains("erp") || e.getKey().toLowerCase().contains("sync"))
                .mapToLong(e -> toLong(e.getValue()))
                .sum();
        Map<String, Object> erp = new LinkedHashMap<>();
        erp.put("reachable", !erpDegraded);
        erp.put("pendingSyncFailures", erpDlqDepth);
        out.put("erp", erp);

        return ResponseEntity.ok(out);
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }
}
