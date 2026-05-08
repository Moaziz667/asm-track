package com.asm.delivery.service;

import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Depot;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DepotRepository;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublicTrackingService {

    private final DeliveryRepository    deliveryRepo;
    private final RouteStopRepository   routeStopRepo;
    private final DepotRepository       depotRepo;
    private final CompanyRepository     companyRepo;
    private final TransportPort         transportPort;

    @Transactional(readOnly = true)
    public TrackingResponse getTracking(UUID deliveryId) {
        Delivery delivery = deliveryRepo.findById(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        Order order = delivery.getOrder();

        // Route stop — get ETA, time windows, route geometry, and linked route
        RouteStop stop = routeStopRepo.findActiveByDeliveryId(deliveryId).orElse(null);

        String startWindow   = null;
        String endWindow     = null;
        String etaAt         = null;
        String routeGeometry = null;
        UUID   driverId      = null;
        UUID   depotId       = null;

        if (stop != null) {
            if (stop.getStartTimeWindow() != null) startWindow = stop.getStartTimeWindow().toString();
            if (stop.getEndTimeWindow()   != null) endWindow   = stop.getEndTimeWindow().toString();
            if (stop.getEtaAt()           != null) etaAt       = stop.getEtaAt().toString();
            routeGeometry = stop.getRouteGeometry();
            if (stop.getRoute() != null) {
                driverId = stop.getRoute().getDriverId();
                depotId  = stop.getRoute().getDepotId();
            }
        }

        // Driver info (name, phone, live position)
        String driverName = null;
        String driverPhone = null;
        Double driverLat = null;
        Double driverLng = null;
        if (driverId != null) {
            try {
                DriverDTO driver = transportPort.getDriver(driverId.toString());
                if (driver != null) {
                    driverName  = driver.getName();
                    driverPhone = driver.getPhone();
                    driverLat   = driver.getCurrentLat();
                    driverLng   = driver.getCurrentLng();
                }
            } catch (Exception e) {
                log.debug("Could not fetch driver for tracking: {}", e.getMessage());
            }
        }

        // Depot
        Double depotLat  = null;
        Double depotLng  = null;
        String depotName = null;
        if (depotId != null) {
            Depot depot = depotRepo.findById(depotId).orElse(null);
            if (depot != null) {
                depotLat  = depot.getLatitude();
                depotLng  = depot.getLongitude();
                depotName = depot.getName();
            }
        }

        // Company branding
        String companyName    = "ASM Track";
        String companyLogoUrl = null;
        if (delivery.getCompanyId() != null) {
            var company = companyRepo.findById(delivery.getCompanyId()).orElse(null);
            if (company != null) {
                companyName    = company.getName();
                companyLogoUrl = company.getLogoUrl();
            }
        }

        return TrackingResponse.builder()
                .deliveryId(deliveryId.toString())
                .status(delivery.getStatus() != null ? delivery.getStatus().name() : "UNKNOWN")
                .clientName(order != null ? order.getClientName() : null)
                .dropoffLat(order != null && order.getDropoffLat()  != null ? order.getDropoffLat().doubleValue()  : null)
                .dropoffLng(order != null && order.getDropoffLng()  != null ? order.getDropoffLng().doubleValue()  : null)
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .dropoffCity(order != null ? order.getDropoffCity() : null)
                .driverName(driverName)
                .driverPhone(driverPhone)
                .driverLat(driverLat)
                .driverLng(driverLng)
                .depotLat(depotLat)
                .depotLng(depotLng)
                .depotName(depotName)
                .startWindow(startWindow)
                .endWindow(endWindow)
                .etaAt(etaAt)
                .routeGeometry(routeGeometry)
                .companyName(companyName)
                .companyLogoUrl(companyLogoUrl)
                .build();
    }
}
