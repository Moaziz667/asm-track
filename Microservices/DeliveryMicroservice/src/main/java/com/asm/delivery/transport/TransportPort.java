package com.asm.delivery.transport;

import java.util.List;

public interface TransportPort {
    List<DriverDTO> getAvailableDrivers();
    DriverDTO getDriver(String driverId);
    boolean setAvailability(String driverId, boolean available);
    boolean updateLocation(String driverId, double lat, double lng);
    boolean incrementStat(String driverId, String field);
}
