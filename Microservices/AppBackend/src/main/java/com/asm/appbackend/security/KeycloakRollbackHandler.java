package com.asm.appbackend.security;

import com.asm.appbackend.client.KeycloakAdminClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakRollbackHandler {

    private final KeycloakAdminClient keycloakAdminClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void handleRollback(KeycloakUserRollbackEvent event) {
        log.warn("Database transaction rolled back. Cleaning up Keycloak user: {}", event.getEmail());
        try {
            keycloakAdminClient.deleteUser(event.getEmail());
            log.info("Successfully cleaned up Keycloak user: {}", event.getEmail());
        } catch (Exception e) {
            log.error("Failed to clean up Keycloak user: {}", event.getEmail(), e);
        }
    }
}
