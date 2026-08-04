package com.asm.appbackend.service;

import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Applies an IAM provisioning command to Keycloak — the single place where IAM intent becomes a
 * Keycloak mutation. Shared by both drivers of the command: AppBackend's own {@code OutboxProcessor}
 * (admin async ops) and the {@code IamCommandConsumer} (driver ops arriving over RabbitMQ). The op
 * is carried as the outbox eventType and as the {@code op} field in broker messages.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IamCommandApplier {

    public static final String PROVISION    = "IAM_PROVISION";
    public static final String UPDATE_EMAIL = "IAM_UPDATE_EMAIL";
    public static final String UPDATE_NAME  = "IAM_UPDATE_NAME";
    public static final String SET_ROLE     = "IAM_SET_ROLE";
    public static final String SET_ENABLED  = "IAM_SET_ENABLED";
    public static final String SET_PICTURE  = "IAM_SET_PICTURE";
    public static final String DELETE       = "IAM_DELETE";
    public static final String LOGOUT       = "IAM_LOGOUT";

    private final KeycloakAdminClient kc;

    public void apply(String op, Map<String, Object> p) {
        if (op == null) { log.warn("IAM command with null op, ignoring: {}", p); return; }
        String appUserId = str(p, "appUserId");
        switch (op) {
            case PROVISION    -> provision(p, appUserId);
            case UPDATE_EMAIL -> kc.updateUserEmail(appUserId, str(p, "oldEmail"), str(p, "email"));
            case UPDATE_NAME  -> kc.updateUserName(appUserId, str(p, "email"), str(p, "name"));
            case SET_ROLE     -> kc.setUserRole(appUserId, str(p, "email"), str(p, "role"));
            case SET_ENABLED  -> kc.setUserEnabled(appUserId, Boolean.TRUE.equals(p.get("enabled")));
            case SET_PICTURE  -> kc.setUserPicture(appUserId, str(p, "picture"));
            case DELETE       -> kc.deleteUser(appUserId);
            case LOGOUT       -> kc.forceLogout(appUserId);
            default           -> log.warn("Unknown IAM op: {}", op);
        }
    }

    /**
     * Provision the Keycloak user AND add them to their tenant's Keycloak Organization. The membership
     * is what makes the user's tokens carry the tenant (org_id / organization claim); without it the API
     * gateway can't resolve X-Company-Id and the user (e.g. a freshly-invited driver) sees no tenant data
     * — every request 404s. The companyId is the current tenant on this IAM command (set from the
     * X-Company-Id header by the RabbitMQ/outbox inbound path) and equals the KC organization id.
     */
    private void provision(Map<String, Object> p, String appUserId) {
        String kcUserId = kc.createUser(str(p, "email"), str(p, "role"), appUserId, null, str(p, "name"), str(p, "phone"));
        UUID companyId = TenantContext.get();
        if (companyId != null && kcUserId != null) {
            kc.addOrganizationMember(companyId.toString(), kcUserId); // idempotent — safe on reconciler re-runs
        } else if (companyId == null) {
            log.warn("IAM PROVISION for appUserId={} with no tenant context — skipping org membership", appUserId);
        }
    }

    private static String str(Map<String, Object> p, String k) {
        Object v = p.get(k);
        return v == null ? null : v.toString();
    }
}
