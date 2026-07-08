package com.asm.delivery.controller;

import com.asm.delivery.dto.response.DashboardKpiResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.AnalyticsPdfService;
import com.asm.delivery.service.DriverPerformancePdfService;
import com.asm.delivery.service.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/reports")
@Tag(name = "Admin Reports", description = "Administrative KPI and reporting endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminReportsController {

    private final ReportingService           reportingService;
    private final com.asm.delivery.service.SystemSettingsService systemSettingsService;
    private final AuditLogService            auditLogService;
    private final AnalyticsPdfService        analyticsPdfService;
    private final DriverPerformancePdfService driverPerformancePdfService;
    private final com.asm.delivery.service.analytics.DriverPerformanceService driverPerformanceService;

    /** Stats freshness: instant from cache, revalidated in the background (analytical, not live). */
    private static final org.springframework.http.CacheControl STATS_CACHE =
            org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(30))
                    .staleWhileRevalidate(java.time.Duration.ofSeconds(120))
                    .cachePrivate();

    @GetMapping("/dashboard")
    @Operation(summary = "Indicateurs de Performance (KPIs) de l'entreprise", 
               description = "Fournit les données de succès (SLA Compliance), volumes et statistiques de retard.")
    public ResponseEntity<DashboardKpiResponse> getDashboard(
            @org.springframework.web.bind.annotation.ModelAttribute com.asm.delivery.dto.analytics.AnalyticsQuery query
    ) {
        return ResponseEntity.ok().cacheControl(STATS_CACHE).body(reportingService.getGlobalKpis(query));
    }

    @GetMapping("/settings")
    @Operation(summary = "Récupérer la configuration actuelle des SLAs", 
               description = "Liste tous les paramètres opérationnels configurables (Temps d'attente max, retards au dépôt, buffers transit).")
    public ResponseEntity<java.util.Map<String, String>> getSettings() {
        return ResponseEntity.ok(systemSettingsService.getAll());
    }

    @PostMapping("/settings")
    @Operation(summary = "Mettre à jour un seuil de SLA en temps réel",
               description = "Permet à l'Admin de modifier dynamiquement les règles de retard du système. Clés valides : 'ops.sla.waiting-limit-minutes', 'ops.sla.assign-limit-minutes', 'ops.sla.pickup-limit-minutes'.")
    public ResponseEntity<Void> updateSetting(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String key,
            @RequestParam String value) {
        systemSettingsService.upsert(key, value);
        auditLogService.logAction(principal, "UPDATE_SYSTEM_SETTING", "SLA_SETTINGS", key,
                java.util.Map.of("parametre", key, "valeur", value, "action", "Mise a jour parametre systeme"));
        return ResponseEntity.ok().build();
    }

    // NOTE: /reports/kpi removed — it duplicated /api/admin/deliveries/stats (same getStats payload).
    // The Analyse page now calls /deliveries/stats directly.

    @GetMapping("/drivers")
    @Operation(summary = "Driver performance scorecards (leaderboard + drilldown)",
            description = "Per-driver volume, success rate, on-time rate, avg delay, top motif and vs-previous "
                    + "deltas. Unified analytics query: granular date (range/last/from/to), compare, and scope "
                    + "(zone/status/motif). Set driverId to get a single-driver drilldown with a daily trend.")
    public ResponseEntity<com.asm.delivery.dto.response.DriverScorecardResponse> driverScorecards(
            @org.springframework.web.bind.annotation.ModelAttribute com.asm.delivery.dto.analytics.AnalyticsQuery query
    ) {
        return ResponseEntity.ok().cacheControl(STATS_CACHE).body(driverPerformanceService.scorecards(query));
    }

    @GetMapping("/analytics/pdf")
    @Operation(summary = "Rapport d'activité globale — PDF")
    public ResponseEntity<byte[]> analyticsPdf(
            @RequestParam(required = false, defaultValue = "today") String period,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        byte[] pdf = analyticsPdfService.generate(period, from, to);
        String filename = "rapport-activite-" + period + "-" + LocalDate.now() + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/drivers/{driverId}/performance/pdf")
    @Operation(summary = "Rapport de performance individuelle chauffeur — PDF")
    public ResponseEntity<byte[]> driverPerformancePdf(
            @PathVariable UUID driverId,
            @RequestParam(required = false, defaultValue = "today") String period,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        byte[] pdf = driverPerformancePdfService.generate(driverId, period, from, to);
        String filename = "performance-chauffeur-" + driverId.toString().substring(0, 8) + "-" + LocalDate.now() + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
