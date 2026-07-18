package com.asm.appbackend.service;

import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.config.TenantSchemaProvisioner;
import com.asm.appbackend.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Orchestrates onboarding a new tenant (company) in one call:
 * <ol>
 *   <li>create the Keycloak Organization → its id is the canonical companyId;</li>
 *   <li>provision the tenant schema on every stateful service (app-backend, delivery, driver);</li>
 *   <li>create the first admin user and add it to the organization.</li>
 * </ol>
 * Any failure rolls back everything provisioned so far (schemas dropped, org deleted) so a half-created
 * tenant never lingers. Registry of tenants stays in Keycloak — no shared companies table.
 */
@Service
@Slf4j
public class TenantOnboardingService {

    private final KeycloakAdminClient kc;
    private final TenantSchemaProvisioner localProvisioner; // app_backend_db
    private final RestClient restClient;

    @Value("${delivery.service.url:http://delivery-service:8082}")
    private String deliveryUrl;

    @Value("${driver.service.url:http://driver-service:8086}")
    private String driverUrl;

    public TenantOnboardingService(KeycloakAdminClient kc,
                                   TenantSchemaProvisioner localProvisioner,
                                   RestClient.Builder restClientBuilder) {
        this.kc = kc;
        this.localProvisioner = localProvisioner;
        this.restClient = restClientBuilder.build();
    }

    public record OnboardResult(String companyId, String adminUserId, List<String> provisioned) {}

    /**
     * @param companyName display name (e.g. "ACME Sousse")
     * @param domain      email domain for the org (nullable)
     * @param adminEmail  first admin's email
     * @param adminName   first admin's display name
     */
    public OnboardResult onboard(String companyName, String domain, String adminEmail, String adminName) {
        String alias = slugify(companyName);
        String orgId = kc.createOrganization(companyName, alias, domain);
        UUID companyId = UUID.fromString(orgId);
        log.info("Onboarding: created KC organization {} (id={})", alias, orgId);

        List<String> provisioned = new ArrayList<>();
        String adminUserId = null;
        try {
            // 1. Provision schema on every stateful service.
            localProvisioner.provision(companyId);
            provisioned.add("app-backend");

            provisionRemote(deliveryUrl, companyId);
            provisioned.add("delivery");

            provisionRemote(driverUrl, companyId);
            provisioned.add("driver");

            // 2. First admin user + organization membership.
            adminUserId = UUID.randomUUID().toString();
            String kcUserId = kc.createUser(adminEmail, "ADMIN", adminUserId, null, adminName);
            kc.addOrganizationMember(orgId, kcUserId);
            log.info("Onboarding complete: company={} admin={}", orgId, adminEmail);

            return new OnboardResult(orgId, adminUserId, provisioned);

        } catch (Exception e) {
            log.error("Onboarding failed for {} — rolling back ({} provisioned)", alias, provisioned, e);
            rollback(companyId, orgId, provisioned, adminUserId);
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Onboarding failed and was rolled back: " + e.getMessage());
        }
    }

    private void provisionRemote(String baseUrl, UUID companyId) {
        try {
            restClient.post()
                    .uri(baseUrl + "/internal/tenants/" + companyId + "/provision")
                    .header("Authorization", "Bearer " + kc.getServiceToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Provision failed at " + baseUrl + ": " + e.getResponseBodyAsString());
        }
    }

    private void deprovisionRemote(String baseUrl, UUID companyId) {
        try {
            restClient.delete()
                    .uri(baseUrl + "/internal/tenants/" + companyId)
                    .header("Authorization", "Bearer " + kc.getServiceToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Rollback deprovision at {} failed for {}: {}", baseUrl, companyId, e.getMessage());
        }
    }

    private void rollback(UUID companyId, String orgId, List<String> provisioned, String adminUserId) {
        if (provisioned.contains("driver")) deprovisionRemote(driverUrl, companyId);
        if (provisioned.contains("delivery")) deprovisionRemote(deliveryUrl, companyId);
        if (provisioned.contains("app-backend")) {
            try { localProvisioner.deprovision(companyId); }
            catch (Exception e) { log.error("Rollback local deprovision failed: {}", e.getMessage()); }
        }
        // Delete the admin user too (idempotent no-op if it was never created) so a failed onboarding
        // leaves no orphan whose email would then block a retry.
        if (adminUserId != null) {
            try { kc.deleteUser(adminUserId); }
            catch (Exception e) { log.error("Rollback delete user {} failed: {}", adminUserId, e.getMessage()); }
        }
        kc.deleteOrganization(orgId);
    }

    /** "ACME Sousse" → "acme-sousse" (KC org alias must be a stable slug). */
    static String slugify(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "tenant-" + UUID.randomUUID().toString().substring(0, 8) : s;
    }
}
