package com.asm.delivery.controller;

import com.asm.delivery.dto.response.AdminStatsResponse;
import com.asm.delivery.dto.response.DashboardKpiResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.analytics.OpsAnalyticsService;
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

    private final OpsAnalyticsService        opsAnalyticsService;
    private final ReportingService           reportingService;
    private final com.asm.delivery.service.SystemSettingsService systemSettingsService;
    private final AuditLogService            auditLogService;
    private final AnalyticsPdfService        analyticsPdfService;
    private final DriverPerformancePdfService driverPerformancePdfService;

    @GetMapping("/dashboard")
    @Operation(summary = "Indicateurs de Performance (KPIs) de l'entreprise", 
               description = "Fournit les données de succès (SLA Compliance), volumes et statistiques de retard.")
    public ResponseEntity<DashboardKpiResponse> getDashboard(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(reportingService.getGlobalKpis(period, from, to));
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

    @GetMapping("/kpi")
    @Operation(summary = "Get KPI report payload for admin dashboard")
    public ResponseEntity<AdminStatsResponse> kpi(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getStats(period, from, to));
    }

    @GetMapping("/analytics/pdf")
    @Operation(summary = "Rapport d'activité globale — PDF")
    public ResponseEntity<byte[]> analyticsPdf(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
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
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        byte[] pdf = driverPerformancePdfService.generate(driverId, period, from, to);
        String filename = "performance-chauffeur-" + driverId.toString().substring(0, 8) + "-" + LocalDate.now() + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
