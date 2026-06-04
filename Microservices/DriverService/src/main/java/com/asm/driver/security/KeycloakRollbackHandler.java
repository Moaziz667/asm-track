package com.asm.driver.security;

import com.asm.driver.client.KeycloakAdminClient;
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
    public void handleRollback(KeycloakDriverRollbackEvent event) {
        log.warn("Database transaction rolled back. Cleaning up Keycloak driver: {}", event.getUsername());
        try {
            keycloakAdminClient.deleteDriver(event.getUsername());
            log.info("Successfully cleaned up Keycloak driver: {}", event.getUsername());
        } catch (Exception e) {
            log.error("Failed to clean up Keycloak driver: {}", event.getUsername(), e);
        }
    }
}
