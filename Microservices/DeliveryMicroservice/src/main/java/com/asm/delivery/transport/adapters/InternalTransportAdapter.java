package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link TransportPort} backed by DriverService internal endpoints via {@link DriverInternalClient}
 * (Feign). Service authentication is handled by the shared Feign interceptor; this adapter only
 * owns the defensive fallbacks (empty list / null / false) so a DriverService blip never breaks
 * dispatch flows.
 */
@Slf4j
@RequiredArgsConstructor
public class InternalTransportAdapter implements TransportPort {

    private final DriverInternalClient driverInternalClient;

    @Override
    public List<DriverDTO> getAvailableDrivers() {
        try {
            List<DriverDTO> body = driverInternalClient.getAvailableDrivers();
            return body != null ? body : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Driver Service getAvailableDrivers failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public DriverDTO getDriver(String driverId) {
        try {
            return driverInternalClient.getDriver(driverId);
        } catch (Exception e) {
            log.warn("Driver Service getDriver({}) failed: {}", driverId, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean driverExists(String driverId) {
        try {
            return driverInternalClient.getDriver(driverId) != null;
        } catch (Exception e) {
            // Spring Cloud CircuitBreaker wraps every Feign call, so a 404 does not arrive as
            // FeignException.NotFound — it arrives as NoFallbackAvailableException with the real
            // cause underneath. Catching only the unwrapped type reported "driver unknown" as
            // "DriverService down", which is the wrong answer to give an operator.
            if (isNotFound(e)) return false;

            // Deliberately NOT a fallback: this gates a write, so an unreachable DriverService must
            // surface as "unknown", never as "does not exist" (which would reject a valid driver
            // during an outage) nor as "exists" (which would let the bad input through).
            log.warn("Driver Service driverExists({}) unreachable: {}", driverId, e.getMessage());
            throw com.asm.delivery.exception.AppException.serviceUnavailable(
                    "DRIVER_SERVICE_UNAVAILABLE",
                    "Impossible de vérifier le chauffeur : le service chauffeurs est injoignable.");
        }
    }

    /** Walks the cause chain, since the circuit-breaker decorator hides the original FeignException. */
    private static boolean isNotFound(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof feign.FeignException fe && fe.status() == 404) return true;
        }
        return false;
    }

    @Override
    public boolean incrementStat(String driverId, String field) {
        try {
            driverInternalClient.incrementStat(driverId, Map.of("field", field));
            return true;
        } catch (Exception e) {
            log.warn("Driver Service incrementStat({}, {}) failed: {}", driverId, field, e.getMessage());
            return false;
        }
    }
}
