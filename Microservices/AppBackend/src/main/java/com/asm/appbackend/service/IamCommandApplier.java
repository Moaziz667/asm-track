package com.asm.appbackend.service;

import com.asm.appbackend.client.KeycloakAdminClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

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
    public static final String DELETE       = "IAM_DELETE";
    public static final String LOGOUT       = "IAM_LOGOUT";

    private final KeycloakAdminClient kc;

    public void apply(String op, Map<String, Object> p) {
        if (op == null) { log.warn("IAM command with null op, ignoring: {}", p); return; }
        String appUserId = str(p, "appUserId");
        switch (op) {
            case PROVISION    -> kc.createUser(str(p, "email"), str(p, "role"), appUserId, null, str(p, "name"), str(p, "phone"));
            case UPDATE_EMAIL -> kc.updateUserEmail(appUserId, str(p, "oldEmail"), str(p, "email"));
            case UPDATE_NAME  -> kc.updateUserName(appUserId, str(p, "email"), str(p, "name"));
            case SET_ROLE     -> kc.setUserRole(appUserId, str(p, "email"), str(p, "role"));
            case SET_ENABLED  -> kc.setUserEnabled(appUserId, Boolean.TRUE.equals(p.get("enabled")));
            case DELETE       -> kc.deleteUser(appUserId);
            case LOGOUT       -> kc.forceLogout(appUserId);
            default           -> log.warn("Unknown IAM op: {}", op);
        }
    }

    private static String str(Map<String, Object> p, String k) {
        Object v = p.get(k);
        return v == null ? null : v.toString();
    }
}
