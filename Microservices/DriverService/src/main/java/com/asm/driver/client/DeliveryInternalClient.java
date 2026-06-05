package com.asm.driver.client;

import com.asm.driver.dto.response.ActiveMissionsDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;
import java.util.UUID;

/**
 * Declarative client for DeliveryService internal endpoints. Service auth (bearer) and caller
 * identity headers are applied globally by {@code ServiceClientConfig}'s Feign interceptor.
 */
@FeignClient(name = "delivery-internal", url = "${delivery.service.url:http://localhost:8082}")
public interface DeliveryInternalClient {

    @GetMapping("/internal/deliveries/active-missions")
    Map<UUID, ActiveMissionsDTO> getActiveMissions();
}
