package com.asm.delivery.controller;

import com.asm.delivery.erp.ErpResyncService;
import com.asm.delivery.service.SystemHealthSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator-facing system health: dead-letter queue depths, Resilience4j circuit-breaker states,
 * database + service reachability, and ERP sync status (failed orders + drill-down). The aggregate
 * is built on a background schedule by {@link SystemHealthSnapshotService} and served from cache, so
 * this endpoint never blocks on network probes. Backs the admin "System Health" page.
 */
@RestController
@RequestMapping("/api/v1/admin/system")
@Tag(name = "Admin System Health", description = "DLQ, circuit breakers, DB and ERP sync health")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminSystemHealthController {

    private final SystemHealthSnapshotService snapshotService;
    private final ErpResyncService erpResyncService;
    private final com.asm.delivery.service.ErpSyncJournalService syncJournalService;

    @GetMapping("/health")
    @Operation(summary = "Aggregated system health for the operator console (cached snapshot)")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(snapshotService.current());
    }

    @GetMapping("/health/history")
    @Operation(summary = "Rolling health history (~1h) for the console's trend sparklines + status timelines")
    public ResponseEntity<List<Map<String, Object>>> healthHistory() {
        return ResponseEntity.ok(snapshotService.history());
    }

    @GetMapping("/erp-sync/history")
    @Operation(summary = "Journal of ERP sync attempts, newest first (all providers, successes and failures)",
            description = "Unlike /health, which reports the current state, this returns every recorded "
                    + "attempt — including failures that were later retried successfully.")
    public ResponseEntity<Map<String, Object>> erpSyncHistory(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean failedOnly) {
        return ResponseEntity.ok(syncJournalService.recent(limit, failedOnly));
    }

    @GetMapping("/erp-sync/history/{orderId}")
    @Operation(summary = "Every ERP sync attempt recorded for one order, newest first")
    public ResponseEntity<List<Map<String, Object>>> erpSyncHistoryForOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(syncJournalService.forOrder(orderId));
    }

    @PostMapping("/erp-sync/{orderId}/resync")
    @PreAuthorize("hasAuthority('perm:erp:resync')")
    @Operation(summary = "Re-drive a single SYNC_FAILED order through the ERP sync path")
    public ResponseEntity<Map<String, Object>> resync(@PathVariable UUID orderId) {
        return ResponseEntity.ok(erpResyncService.resync(orderId).toMap());
    }

    @PostMapping("/erp-sync/resync-all")
    @PreAuthorize("hasAuthority('perm:erp:resync')")
    @Operation(summary = "Re-drive up to {max} of the oldest SYNC_FAILED orders")
    public ResponseEntity<Map<String, Object>> resyncAll(@RequestParam(defaultValue = "50") int max) {
        return ResponseEntity.ok(erpResyncService.resyncAll(max));
    }
}
