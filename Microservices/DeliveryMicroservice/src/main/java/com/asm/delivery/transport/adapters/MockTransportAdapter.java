package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@Slf4j
public class MockTransportAdapter implements TransportPort {

    @Override
    public List<DriverDTO> getAvailableDrivers() {
        return List.of(DriverDTO.builder()
                .id(UUID.randomUUID().toString())
                .name("Mock Driver")
                .phone("+21600000000")
                .build());
    }

    @Override
    public DriverDTO getDriver(String driverId) {
        return DriverDTO.builder()
                .id(driverId)
                .name("Mock Driver")
                .phone("+21600000000")
                .build();
    }

    @Override
    public boolean updateLocation(String driverId, double lat, double lng) {
        log.info("[Mock] updateLocation driverId={} lat={} lng={}", driverId, lat, lng);
        return true;
    }

    @Override
    public boolean incrementStat(String driverId, String field) {
        log.info("[Mock] incrementStat driverId={} field={}", driverId, field);
        return true;
    }
}
