package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.DriverDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Declarative client for DriverService internal endpoints. Service auth + caller-identity headers
 * are applied globally by {@code ServiceClientConfig}'s Feign interceptor.
 *
 * <p>The mutating calls ({@code updateLocation}, {@code incrementStat}) are slated to move to
 * RabbitMQ in Phase 2; they remain HTTP here so the RestTemplate removal in Phase 1 is behavior-preserving.
 */
@FeignClient(name = "driver-internal", url = "${driver.service.url:http://driver-service:8086}")
public interface DriverInternalClient {

    @GetMapping("/internal/drivers/available")
    List<DriverDTO> getAvailableDrivers();

    @GetMapping("/internal/drivers/{driverId}")
    DriverDTO getDriver(@PathVariable("driverId") String driverId);

    @PutMapping("/internal/drivers/{driverId}/location")
    void updateLocation(@PathVariable("driverId") String driverId, @RequestBody Map<String, Object> body);

    @PostMapping("/internal/drivers/{driverId}/stats/increment")
    void incrementStat(@PathVariable("driverId") String driverId, @RequestBody Map<String, Object> body);
}
