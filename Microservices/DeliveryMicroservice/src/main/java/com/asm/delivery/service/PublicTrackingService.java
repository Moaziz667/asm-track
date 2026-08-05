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
import com.asm.delivery.repository.RmaRepository;
import com.asm.delivery.entity.Rma;
import com.asm.delivery.entity.RmaStatus;
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
    private final com.asm.delivery.storage.MediaUrlResolver mediaUrlResolver;
    private final RouteStopRepository   routeStopRepo;
    private final DepotRepository       depotRepo;
    private final CompanyRepository     companyRepo;
    private final RmaRepository         rmaRepo;
    private final TransportPort         transportPort;

    /**
     * How long a finished delivery stays publicly readable.
     *
     * <p>The link carries no authentication — the UUID <i>is</i> the credential — and it used to work
     * forever. A tracking URL forwarded once, or left in a browser history, kept returning a
     * customer's name, phone and address indefinitely. Loi organique 2004-63 asks that personal data
     * be kept no longer than the purpose requires, and the purpose here ends with the delivery.
     *
     * <p>Two weeks rather than two days: a customer chasing a partial delivery or a return comes
     * back to this page days later, and a link that has gone dead is a support call.
     */
    private static final java.time.Duration PUBLIC_LINK_TTL = java.time.Duration.ofDays(14);

    @Transactional(readOnly = true)
    public TrackingResponse getTracking(UUID deliveryId) {
        TrackingData data = doGetTrackingData(deliveryId);
        Delivery tracked = data.delivery();
        boolean finished = isTerminal(tracked.getStatus());

        if (finished && isPastRetention(tracked)) {
            // Deliberately the same answer as an unknown id: telling a stranger "this one expired"
            // confirms the delivery existed, which is the one bit the UUID was protecting.
            throw AppException.notFound("Delivery not found");
        }

        // Driver info - OUTSIDE transaction
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
                    // The live position answers "where is my parcel", and once the parcel has
                    // arrived that question is closed. Continuing to publish it would broadcast an
                    // employee's whereabouts on his next rounds to whoever still holds the link —
                    // the name and phone stay so the customer can still reach him about the drop.
                    if (!finished) {
                        driverLat = driver.getCurrentLat();
                        driverLng = driver.getCurrentLng();
                    }
                }
            } catch (Exception e) {
                log.debug("Could not fetch driver for tracking: {}", e.getMessage());
            }
        }

        // Failure reason — only meaningful when the attempt failed or was partial.
        Delivery delivery = data.delivery();
        String failReason = null;
        if (delivery.getStatus() == com.asm.delivery.entity.DeliveryStatus.FAILED
                || delivery.getStatus() == com.asm.delivery.entity.DeliveryStatus.PARTIALLY_DELIVERED) {
            failReason = delivery.getFailReason();
        }

        // Latest return (RMA) lifecycle state for this delivery, if any. On a REJECTED return, surface the
        // resolution note so the client sees WHY it was refused.
        Rma latestReturn = rmaRepo.findByDeliveryIdOrderByCreatedAtDesc(deliveryId).stream()
                .findFirst().orElse(null);
        String returnStatus = latestReturn != null ? latestReturn.getStatus().name() : null;
        String returnResolutionNote = (latestReturn != null && latestReturn.getStatus() == RmaStatus.REJECTED)
                ? latestReturn.getResolutionNote() : null;

        Order order = data.delivery().getOrder();
        // ADR-033 — a return collection shows the RMA lines (returned qty), not the shared order's lines.
        List<OrderItem> displayItems = data.delivery().getKind() == com.asm.delivery.entity.DeliveryKind.RETURN_PICKUP
                && data.delivery().getRmaId() != null
                ? rmaRepo.findById(data.delivery().getRmaId())
                    .map(r -> com.asm.delivery.service.RmaService.toOrderItems(r.getItems())).orElse(List.of())
                : (order != null ? order.getItems() : null);
        List<TrackingResponse.OrderItemDto> itemDtos = null;
        if (displayItems != null) {
            itemDtos = displayItems.stream()
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
                .kind(data.delivery().getKind() != null ? data.delivery().getKind().name() : "FORWARD")
                .failReason(failReason)
                .returnStatus(returnStatus)
                .returnResolutionNote(returnResolutionNote)
                .clientName(order != null ? order.getClientName() : null)
                .clientPhone(order != null ? order.getClientPhone() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .customerRef(order != null ? order.getCustomerRef() : null)
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

    private static boolean isTerminal(com.asm.delivery.entity.DeliveryStatus s) {
        return s == com.asm.delivery.entity.DeliveryStatus.DELIVERED
                || s == com.asm.delivery.entity.DeliveryStatus.PARTIALLY_DELIVERED
                || s == com.asm.delivery.entity.DeliveryStatus.FAILED
                || s == com.asm.delivery.entity.DeliveryStatus.CANCELLED;
    }

    /**
     * Whether a finished delivery has outlived its public link.
     *
     * <p>Dated from whichever end-of-attempt timestamp exists, falling back to {@code updatedAt} — a
     * cancellation writes neither of the first two. A row with no timestamp at all is kept readable
     * rather than hidden: a missing date is a bug in our own writes, and expiring on it would take
     * the page away from a customer whose parcel is still coming.
     */
    private static boolean isPastRetention(Delivery d) {
        java.time.LocalDateTime finishedAt = d.getCompletedAt() != null ? d.getCompletedAt()
                : d.getFailedAt() != null ? d.getFailedAt()
                : d.getUpdatedAt();
        return finishedAt != null
                && finishedAt.isBefore(java.time.LocalDateTime.now().minus(PUBLIC_LINK_TTL));
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

        // Single-tenant per instance: branding comes from the sole company row (if configured).
        String companyName    = "ASM Track";
        String companyLogoUrl = null;
        var company = companyRepo.findAll().stream().findFirst().orElse(null);
        if (company != null) {
            if (company.getName() != null && !company.getName().isBlank()) companyName = company.getName();
            companyLogoUrl = mediaUrlResolver.toPublicUrl(company.getLogoUrl());
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
