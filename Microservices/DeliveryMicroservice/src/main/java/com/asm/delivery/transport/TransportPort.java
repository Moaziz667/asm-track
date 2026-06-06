package com.asm.delivery.transport;

import java.util.List;

public interface TransportPort {
    List<DriverDTO> getAvailableDrivers();
    DriverDTO getDriver(String driverId);
    boolean incrementStat(String driverId, String field);
}
