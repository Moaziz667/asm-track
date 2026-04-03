package com.asm.delivery.controller;

import com.asm.delivery.dto.response.AdminStatsResponse;
import com.asm.delivery.service.AdminDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/reports")
@Tag(name = "Admin Reports", description = "Administrative KPI and reporting endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminReportsController {

    private final AdminDeliveryService adminDeliveryService;

    @GetMapping("/kpi")
    @Operation(summary = "Get KPI report payload for admin dashboard")
    public ResponseEntity<AdminStatsResponse> kpi(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(adminDeliveryService.getStats(period, from, to));
    }
}
