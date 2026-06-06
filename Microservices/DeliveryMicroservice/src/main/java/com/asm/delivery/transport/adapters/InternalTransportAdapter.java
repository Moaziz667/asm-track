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
