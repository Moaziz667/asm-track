package com.asm.appbackend.service;

import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.config.TenantSchemaProvisioner;
import com.asm.appbackend.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import org.mockito.Answers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link TenantOnboardingService} — onboarding a tenant in one call and, above all, its
 * <b>rollback</b>: a half-created tenant (org without schemas, or schemas without a user) is a support
 * nightmare and a security grey-zone. The invariant we lock here: if <em>any</em> step fails, everything
 * created so far is undone (schemas dropped, user + org deleted).
 *
 * <p>This is a collaborator-contract test (Mockito): Keycloak and the remote provision HTTP calls are
 * mocked, so it asserts the orchestration decisions, not the real KC/DB round-trip (validated live E2E).
 * The remote {@link RestClient} POST chain is stubbed to return 200 (see {@code setUp}), so a provision
 * "succeeds" without a real server, letting us fail a LATER step and observe the compensating rollback.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // orchestration touches collaborators a variable number of times
class TenantOnboardingServiceTest {

    @Mock KeycloakAdminClient kc;
    @Mock TenantSchemaProvisioner localProvisioner;
    @Mock RestClient.Builder restClientBuilder;
    @Mock RestClient restClient;
    // RETURNS_SELF lets the fluent builder chain (post().uri().header().contentType()) return itself,
    // so we only have to stub the two terminal calls (retrieve → toBodilessEntity).
    @Mock(answer = Answers.RETURNS_SELF) RequestBodyUriSpec requestSpec;
    @Mock ResponseSpec responseSpec;

    private TenantOnboardingService service;

    private static final String ORG_ID = "5c72a175-6f18-4b61-8d21-ff7fd15938b3";

    @BeforeEach
    void setUp() {
        // The service builds its RestClient from the injected builder in the constructor, so stub build()
        // before constructing it (this is why we don't use @InjectMocks here).
        when(restClientBuilder.build()).thenReturn(restClient);
        service = new TenantOnboardingService(kc, localProvisioner, restClientBuilder);
        ReflectionTestUtils.setField(service, "deliveryUrl", "http://delivery-service:8082");
        ReflectionTestUtils.setField(service, "driverUrl", "http://driver-service:8086");

        // Remote provision POST chain succeeds (200). deprovisionRemote (DELETE) is left unstubbed — its
        // own try/catch swallows the resulting no-op, which is fine for the rollback assertions.
        when(restClient.post()).thenReturn(requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    // ── slugify: KC org alias must be a stable slug ──────────────────────────
    @Nested
    @DisplayName("slugify")
    class Slugify {
        @Test
        void lowercasesAndDashes() {
            assertThat(TenantOnboardingService.slugify("ACME Sousse")).isEqualTo("acme-sousse");
        }

        @Test
        void collapsesNonAlphanumericRuns_andTrimsEdges() {
            assertThat(TenantOnboardingService.slugify("  ACME  --  Tunis!! ")).isEqualTo("acme-tunis");
        }

        @Test
        void blankNameFallsBackToAGeneratedSlug() {
            assertThat(TenantOnboardingService.slugify("   ")).startsWith("tenant-");
        }
    }

    // ── happy path ───────────────────────────────────────────────────────────
    @Test
    @DisplayName("Happy path: creates org, provisions all 3 schemas, creates admin + membership, no rollback")
    void onboardsEndToEnd() {
        when(kc.createOrganization(eq("ACME Sousse"), eq("acme-sousse"), anyString())).thenReturn(ORG_ID);
        when(kc.createUser(anyString(), eq("ADMIN"), anyString(), any(), anyString())).thenReturn("kc-user-1");

        TenantOnboardingService.OnboardResult result =
                service.onboard("ACME Sousse", "sousse.acme.tn", "admin@sousse.acme.tn", "Sousse Admin");

        assertThat(result.companyId()).isEqualTo(ORG_ID);
        assertThat(result.provisioned()).containsExactly("app-backend", "delivery", "driver");

        UUID companyId = UUID.fromString(ORG_ID);
        verify(localProvisioner).provision(companyId);            // app-backend schema
        verify(kc).addOrganizationMember(eq(ORG_ID), eq("kc-user-1"));
        // no failure → no compensating actions
        verify(kc, never()).deleteOrganization(anyString());
        verify(localProvisioner, never()).deprovision(any());
    }

    // ── rollback ───────────────────────────────────────────────────────────
    @Test
    @DisplayName("Local schema provision fails early → org is deleted, no user created, nothing deprovisioned")
    void rollsBackWhenLocalProvisionFails() {
        when(kc.createOrganization(anyString(), anyString(), any())).thenReturn(ORG_ID);
        doThrow(new RuntimeException("app_db unreachable")).when(localProvisioner).provision(any());

        assertThatThrownBy(() ->
                service.onboard("ACME Sousse", null, "admin@sousse.acme.tn", "Sousse Admin"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("rolled back");

        // The only thing created was the org → it must be deleted. Nothing else ran.
        verify(kc).deleteOrganization(ORG_ID);
        verify(kc, never()).createUser(anyString(), anyString(), anyString(), any(), anyString());
        verify(localProvisioner, never()).deprovision(any()); // app-backend was never marked provisioned
    }

    @Test
    @DisplayName("Admin-user creation fails after all schemas → every schema is dropped and the org deleted")
    void rollsBackAllSchemasWhenUserCreationFails() {
        when(kc.createOrganization(anyString(), anyString(), any())).thenReturn(ORG_ID);
        // all 3 provisions succeed (local + the two remote deep-stub calls), then the user step blows up
        when(kc.createUser(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("KC user endpoint down"));

        assertThatThrownBy(() ->
                service.onboard("ACME Sousse", "sousse.acme.tn", "admin@sousse.acme.tn", "Sousse Admin"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("rolled back");

        UUID companyId = UUID.fromString(ORG_ID);
        verify(localProvisioner).deprovision(companyId); // app-backend schema dropped
        verify(kc).deleteOrganization(ORG_ID);           // org removed
    }
}
