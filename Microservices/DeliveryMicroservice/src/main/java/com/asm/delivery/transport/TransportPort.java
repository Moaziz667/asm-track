package com.asm.delivery.transport;

import java.util.List;

public interface TransportPort {
    List<DriverDTO> getAvailableDrivers();
    DriverDTO getDriver(String driverId);
    boolean incrementStat(String driverId, String field);

    /**
     * Whether DriverService knows this driver.
     *
     * <p>Unlike {@link #getDriver(String)} — which degrades to {@code null} on any failure so a
     * DriverService blip never breaks a read — this is an <b>authoritative</b> check meant to gate a
     * write. It therefore keeps apart the two cases that must not be conflated: a driver that does
     * not exist (returns {@code false}) and a DriverService that could not be reached (throws). A
     * caller validating untrusted input has to reject the first and retry the second; collapsing
     * both into {@code null} would either let bad input through or reject valid input during an
     * outage.
     *
     * @throws com.asm.delivery.exception.AppException 503 when DriverService is unreachable
     */
    boolean driverExists(String driverId);
}
