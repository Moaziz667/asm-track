package com.asm.appbackend.controller;

import com.asm.appbackend.service.TenantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Platform (ops) endpoint to onboard a new tenant in one call. Secured to the SERVICE role — a tenant's
 * own admin cannot create other tenants; this is an operator/self-signup-orchestrator action.
 */
@RestController
@RequestMapping("/internal/onboarding")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Internal · Tenant Onboarding", description = "Platform/ops endpoint (SERVICE role) to onboard a new "
        + "tenant in one call — creates the Keycloak organization, the first admin, and the tenant schema. Not "
        + "reachable by a tenant's own admin.")
@SecurityRequirement(name = "bearerAuth")
public class TenantOnboardingController {

    private final TenantOnboardingService onboarding;

    @Data
    public static class OnboardRequest {
        @NotBlank private String companyName;
        private String domain;
        @NotBlank @Email private String adminEmail;
        @NotBlank private String adminName;
    }

    @PostMapping
    @Operation(summary = "Onboard a new tenant",
            description = "Provisions a complete new tenant: Keycloak organization, first admin user (added to "
                    + "the org), and the company's database schema. Returns the created ids. Best-effort rollback "
                    + "on partial failure.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant onboarded (ids returned)"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Caller is not a SERVICE principal")
    })
    public ResponseEntity<TenantOnboardingService.OnboardResult> onboard(@RequestBody OnboardRequest req) {
        return ResponseEntity.ok(onboarding.onboard(
                req.getCompanyName(), req.getDomain(), req.getAdminEmail(), req.getAdminName()));
    }
}
