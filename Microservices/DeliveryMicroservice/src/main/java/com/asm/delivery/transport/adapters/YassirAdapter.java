package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

@Slf4j
public class YassirAdapter implements TransportPort {

    @Override
    public List<DriverDTO> getAvailableDrivers() {
        log.info("TODO: Yassir API — getAvailableDrivers");
        return Collections.emptyList();
    }

    @Override
    public DriverDTO getDriver(String driverId) {
        log.info("TODO: Yassir API — getDriver({})", driverId);
        return null;
    }

    @Override
    public boolean setAvailability(String driverId, boolean available) {
        log.info("TODO: Yassir API — setAvailability({}, {})", driverId, available);
        return true;
    }

    @Override
    public boolean updateLocation(String driverId, double lat, double lng) {
        log.info("TODO: Yassir API — updateLocation({})", driverId);
        return true;
    }

    @Override
    public boolean incrementStat(String driverId, String field) {
        log.info("TODO: Yassir API — incrementStat({}, {})", driverId, field);
        return true;
    }
}
