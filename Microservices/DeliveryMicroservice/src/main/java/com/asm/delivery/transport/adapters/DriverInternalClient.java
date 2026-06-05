package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.DriverDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Declarative client for DriverService internal endpoints (synchronous reads + the outbox-driven
 * stat increment). Service auth + caller-identity headers are applied globally by
 * {@code ServiceClientConfig}'s Feign interceptor. Live location updates are no longer here — they
 * are published to the {@code driver.commands} exchange (fire-and-forget) by {@code DriverCommandPublisher}.
 */
@FeignClient(name = "driver-internal", url = "${driver.service.url:http://driver-service:8086}")
public interface DriverInternalClient {

    @GetMapping("/internal/drivers/available")
    List<DriverDTO> getAvailableDrivers();

    @GetMapping("/internal/drivers/{driverId}")
    DriverDTO getDriver(@PathVariable("driverId") String driverId);

    @PostMapping("/internal/drivers/{driverId}/stats/increment")
    void incrementStat(@PathVariable("driverId") String driverId, @RequestBody Map<String, Object> body);
}
