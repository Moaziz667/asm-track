package com.asm.driver.port;

/**
 * Transport Port Interface Documentation
 * 
 * This service implements the internal transport provider for the ASM ecosystem.
 * Deliver Service's `InternalTransportAdapter` will make HTTP calls to the 
 * `/internal/drivers/**` endpoints to implement an interface similar to this.
 * 
 * Future external transport providers (e.g. Lalamove, Yassir) will implement 
 * this exact same behavior within DeliveryService, decoupling business logic 
 * from the fleet implementation.
 */
public interface TransportPort {
    // List<DriverDTO> getAvailableDrivers();
    // DriverDTO getDriver(String driverId);
    // boolean updateLocation(String driverId, double lat, double lng);
    // boolean incrementStat(String driverId, String field);
    
    // Stub classes for DeliveryService:
    // class InternalTransportAdapter implements TransportPort { ... calls this service }
    // class LalamoveAdapter implements TransportPort { ... calls Lalamove API }
    // class YassirAdapter implements TransportPort { ... calls Yassir Business API }
}
