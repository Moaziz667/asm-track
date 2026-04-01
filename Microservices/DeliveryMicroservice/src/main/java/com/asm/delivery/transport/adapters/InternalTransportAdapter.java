package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class InternalTransportAdapter implements TransportPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalSecret;

    public InternalTransportAdapter(String baseUrl, String internalSecret) {
        this.restTemplate   = new RestTemplate();
        this.baseUrl        = baseUrl;
        this.internalSecret = internalSecret;
    }

    // ── getAvailableDrivers ───────────────────────────────────────────────────

    @Override
    public List<DriverDTO> getAvailableDrivers() {
        try {
            ResponseEntity<List<DriverDTO>> resp = restTemplate.exchange(
                    baseUrl + "/internal/drivers/available",
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    new ParameterizedTypeReference<>() {}
            );
            List<DriverDTO> body = resp.getBody();
            return body != null ? body : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Driver Service getAvailableDrivers failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── getDriver ─────────────────────────────────────────────────────────────

    @Override
    public DriverDTO getDriver(String driverId) {
        try {
            ResponseEntity<DriverDTO> resp = restTemplate.exchange(
                    baseUrl + "/internal/drivers/" + driverId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    DriverDTO.class
            );
            return resp.getBody();
        } catch (Exception e) {
            log.warn("Driver Service getDriver({}) failed: {}", driverId, e.getMessage());
            return null;
        }
    }

    // ── setAvailability ───────────────────────────────────────────────────────

    @Override
    public boolean setAvailability(String driverId, boolean available) {
        try {
            restTemplate.exchange(
                    baseUrl + "/internal/drivers/" + driverId + "/availability",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("available", available), headers()),
                    Void.class
            );
            return true;
        } catch (Exception e) {
            log.warn("Driver Service setAvailability({}, {}) failed: {}", driverId, available, e.getMessage());
            return false;
        }
    }

    // ── updateLocation ────────────────────────────────────────────────────────

    @Override
    public boolean updateLocation(String driverId, double lat, double lng) {
        try {
            restTemplate.exchange(
                    baseUrl + "/internal/drivers/" + driverId + "/location",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("lat", lat, "lng", lng), headers()),
                    Void.class
            );
            return true;
        } catch (Exception e) {
            log.warn("Driver Service updateLocation({}) failed: {}", driverId, e.getMessage());
            return false;
        }
    }

    // ── incrementStat ─────────────────────────────────────────────────────────

    @Override
    public boolean incrementStat(String driverId, String field) {
        try {
            restTemplate.exchange(
                    baseUrl + "/internal/drivers/" + driverId + "/stats/increment",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("field", field), headers()),
                    Void.class
            );
            return true;
        } catch (Exception e) {
            log.warn("Driver Service incrementStat({}, {}) failed: {}", driverId, field, e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Internal-Secret", internalSecret);
        return h;
    }
}
