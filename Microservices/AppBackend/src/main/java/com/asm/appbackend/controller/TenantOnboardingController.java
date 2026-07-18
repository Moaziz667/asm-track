package com.asm.appbackend.controller;

import com.asm.appbackend.service.TenantOnboardingService;
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
    public ResponseEntity<TenantOnboardingService.OnboardResult> onboard(@RequestBody OnboardRequest req) {
        return ResponseEntity.ok(onboarding.onboard(
                req.getCompanyName(), req.getDomain(), req.getAdminEmail(), req.getAdminName()));
    }
}
