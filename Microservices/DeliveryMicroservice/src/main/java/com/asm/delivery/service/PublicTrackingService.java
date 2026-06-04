package com.asm.delivery.service;

import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Depot;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.RouteStop;
import java.util.List;
import java.util.stream.Collectors;
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
        TrackingData data = doGetTrackingData(deliveryId);

        // Driver info (name, phone, live position) - OUTSIDE transaction
        String driverName = null;
        String driverPhone = null;
        Double driverLat = null;
        Double driverLng = null;
        if (data.driverId() != null) {
            try {
                DriverDTO driver = transportPort.getDriver(data.driverId().toString());
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

        Order order = data.delivery().getOrder();
        List<TrackingResponse.OrderItemDto> itemDtos = null;
        if (order != null && order.getItems() != null) {
            itemDtos = order.getItems().stream()
                .map(i -> TrackingResponse.OrderItemDto.builder()
                    .name(i.getName())
                    .quantity(i.getQuantity())
                    .unitPrice(i.getUnitPrice() != null ? i.getUnitPrice().doubleValue() : null)
                    .build())
                .collect(Collectors.toList());
        }

        return TrackingResponse.builder()
                .deliveryId(deliveryId.toString())
                .status(data.delivery().getStatus() != null ? data.delivery().getStatus().name() : "UNKNOWN")
                .clientName(order != null ? order.getClientName() : null)
                .clientPhone(order != null ? order.getClientPhone() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .dropoffLat(order != null && order.getDropoffLat() != null ? order.getDropoffLat().doubleValue() : null)
                .dropoffLng(order != null && order.getDropoffLng() != null ? order.getDropoffLng().doubleValue() : null)
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .dropoffCity(order != null ? order.getDropoffCity() : null)
                .driverName(driverName)
                .driverPhone(driverPhone)
                .driverLat(driverLat)
                .driverLng(driverLng)
                .depotLat(data.depotLat())
                .depotLng(data.depotLng())
                .depotName(data.depotName())
                .startWindow(data.startWindow())
                .endWindow(data.endWindow())
                .etaAt(data.etaAt())
                .routeGeometry(data.routeGeometry())
                .companyName(data.companyName())
                .companyLogoUrl(data.companyLogoUrl())
                .totalAmount(order != null && order.getTotalAmount() != null ? order.getTotalAmount().doubleValue() : null)
                .items(itemDtos)
                .build();
    }

    @Transactional(readOnly = true)
    public TrackingData doGetTrackingData(UUID deliveryId) {
        Delivery delivery = deliveryRepo.findById(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

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

        String companyName    = "ASM Track";
        String companyLogoUrl = null;
        if (null != null) {
            var company = companyRepo.findById(null).orElse(null);
            if (company != null) {
                companyName    = company.getName();
                companyLogoUrl = company.getLogoUrl();
            }
        }

        return new TrackingData(delivery, startWindow, endWindow, etaAt, routeGeometry, driverId, depotLat, depotLng, depotName, companyName, companyLogoUrl);
    }

    private record TrackingData(
            Delivery delivery,
            String startWindow,
            String endWindow,
            String etaAt,
            String routeGeometry,
            UUID driverId,
            Double depotLat,
            Double depotLng,
            String depotName,
            String companyName,
            String companyLogoUrl
    ) {}
}
