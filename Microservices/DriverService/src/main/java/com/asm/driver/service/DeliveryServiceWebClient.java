package com.asm.driver.service;

import com.asm.driver.client.DeliveryInternalClient;
import com.asm.driver.dto.response.ActiveMissionsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Thin caching wrapper over {@link DeliveryInternalClient}. Service authentication and the
 * client_credentials token are handled by the shared Feign interceptor (ServiceClientConfig),
 * so this class only owns the 5-second result cache and the serve-stale-on-failure behaviour.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryServiceWebClient {

    private final DeliveryInternalClient deliveryInternalClient;

    private Map<UUID, ActiveMissionsDTO> cachedMissions;
    private Instant cacheExpiresAt = Instant.MIN;

    public synchronized Map<UUID, ActiveMissionsDTO> getActiveMissions() {
        if (cachedMissions != null && Instant.now().isBefore(cacheExpiresAt)) {
            return cachedMissions;
        }
        try {
            Map<UUID, ActiveMissionsDTO> body = deliveryInternalClient.getActiveMissions();
            cachedMissions = body != null ? body : Collections.emptyMap();
            cacheExpiresAt = Instant.now().plusSeconds(5); // Cache for 5 seconds
            return cachedMissions;
        } catch (Exception e) {
            log.warn("Failed to retrieve active driver missions from Delivery Service: {}. Serving stale cache.", e.getMessage());
            return cachedMissions != null ? cachedMissions : Collections.emptyMap();
        }
    }
}
