package com.asm.delivery.service.dispatch;

import com.asm.delivery.entity.Order;
import java.time.LocalTime;
import java.util.stream.Collectors;

import com.asm.delivery.dto.request.AdminExceptionReassignRequest;
import com.asm.delivery.dto.request.AdminExceptionReplanRequest;
import com.asm.delivery.dto.response.AdminDeliveryDetailResponse;
import com.asm.delivery.dto.response.AdminOpsExceptionsResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.erp.ErpSyncService;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.service.EventPublisher;
import com.asm.delivery.service.RouteOptimizationService;
import com.asm.delivery.service.route.RouteWebSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ExceptionResolutionService {

    private static final List<DeliveryStatus> REASSIGN_ALLOWED_STATUSES = List.of(
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP
    );

    private static final List<DeliveryStatus> REPLAN_ALLOWED_STATUSES = List.of(
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.FAILED
    );

    private final DeliveryRepository deliveryRepo;
    private final TransportPort transportPort;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final OrderRepository orderRepo;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final AuditLogService auditLogService;
    private final EventPublisher eventPublisher;
    private final DelayCalculationService delayCalculationService;
    private final RouteOptimizationService routeOptimizationService;
    private final ZoneRepository zoneRepository;
    private final DispatchService dispatchService;
    private final RouteWebSocketService routeWebSocketService;
    private final ErpSyncService erpSyncService;

        @Transactional
        public AdminOpsExceptionsResponse.ExceptionItem reassignException(UUID deliveryId,
                                                                                                                          AdminExceptionReassignRequest request,
                                                                                                                          UserPrincipal principal) {
                Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                                .orElseThrow(() -> AppException.notFound("Delivery not found"));

                assertReassignAllowed(delivery);

                // When a parcel is already picked up, reassignment implies a physical handover.
                // We require a note to keep custody changes explicit in the audit trail.
                if (delivery.getStatus() == DeliveryStatus.PICKED_UP && !StringUtils.hasText(request.getNote())) {
                        throw AppException.badRequest("A handover note is required to reassign a picked-up delivery");
                }

                if (request.getDriverId().equals(delivery.getDriverId())) {
                        throw AppException.badRequest("Delivery is already assigned to this driver");
                }

                DriverDTO targetDriver = transportPort.getDriver(request.getDriverId().toString());
                if (targetDriver == null) {
                        throw AppException.badRequest("Target driver not found");
                }

                DeliveryStatus previousStatus = delivery.getStatus();
                UUID previousDriverId = delivery.getDriverId();

                LocalDateTime now = LocalDateTime.now();
                boolean wasPickedUp = previousStatus == DeliveryStatus.PICKED_UP;
                delivery.setDriverId(request.getDriverId());
                delivery.setStatus(DeliveryStatus.SCHEDULED);
                delivery.setAssignedAt(now);
                // Keep pickedUpAt as audit record if the parcel was already collected by the previous driver
                if (!wasPickedUp) {
                    delivery.setPickedUpAt(null);
                }
                delivery.setInTransitAt(null);
                delivery.setCompletedAt(null);
                delivery.setWaitingSlaMinutes(delayCalculationService.calculateWaitingSlaMinutes(delivery));
                delivery.setAssignSlaMinutes(null);
                delivery.setPickupSlaMinutes(null);
                delivery.setCancelledAt(null);
                delivery.setCancelReason(null);
                delivery.setCancelledBy(null);
                // failedAt and failureCode are kept for historical reporting

                deliveryRepo.save(delivery);

                ActorInfo actor = resolveActor(principal);
                String previousDriverName = "Inconnu";
                if (previousDriverId != null) {
                        DriverDTO prevDriver = transportPort.getDriver(previousDriverId.toString());
                        if (prevDriver != null) {
                                previousDriverName = prevDriver.getName() != null ? prevDriver.getName() : "Inconnu";
                        }
                }

                String targetDriverName = targetDriver.getName() != null ? targetDriver.getName() : "Inconnu";
                String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "Inconnu";

                Map<String, Object> auditDetails = Map.of(
                        "action", "reassign",
                        "delivery", shortDeliveryId(delivery.getId()),
                        "client", clientName,
                        "fromDriver", previousDriverName,
                        "toDriver", targetDriverName,
                        "reason", request.getNote() != null ? request.getNote() : ""
                );

                auditLogService.logAction(principal, "REASSIGN_DELIVERY", "DELIVERY", delivery.getId().toString(), auditDetails);

                // Keep route plan consistent with ownership change: move stop to the new driver's route.
                Set<UUID> affectedRouteIds = moveStopToDriverRoute(delivery, request.getDriverId(), actor.name(), request.getStartTimeWindow(), request.getEndTimeWindow());

                // Recompute ETAs/geometries on both source and target routes after ownership change.
                for (UUID routeId : affectedRouteIds) {
                        if (routeId == null) continue;
                        routeOptimizationService.recalculate(routeId);
                }

                if (previousDriverId != null && !previousDriverId.equals(request.getDriverId())) {
                        auditLogService.logAction(principal, "DELIVERY_REMOVED_FROM_ROUTE", "DELIVERY", delivery.getId().toString(),
                                Map.of("action", "removed_by_reassign", "fromDriver", previousDriverName, "toDriver", targetDriverName,
                                        "client", clientName));
                }

                appendHistory(delivery,
                                DeliveryStatus.SCHEDULED,
                                actor.name(),
                                actor.role(),
                                buildReassignOpsNote(previousStatus, previousDriverName, targetDriverName, request.getNote()));

                eventPublisher.publishDeliveryReassigned(delivery.getOrder(), delivery, previousDriverId, request.getDriverId());

                return mapActionResult(delivery, "WARNING", "RESCHEDULED", "Delivery reassigned to a new driver");
        }

        @Transactional
        public AdminOpsExceptionsResponse.ExceptionItem replanException(UUID deliveryId,
                                                                                                                        AdminExceptionReplanRequest request,
                                                                                                                        UserPrincipal principal) {
                Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                                .orElseThrow(() -> AppException.notFound("Delivery not found"));

                assertReplanAllowed(delivery);

                DeliveryStatus previousStatus = delivery.getStatus();
                UUID previousDriverId = delivery.getDriverId();
                if (delivery.getDriverId() != null) {
                }

                delivery.setDriverId(null);
                delivery.setStatus(DeliveryStatus.UNSCHEDULED);
                delivery.setAssignedAt(null);
                delivery.setPickedUpAt(null);
                delivery.setInTransitAt(null);
                delivery.setCompletedAt(null);
                delivery.setWaitingSlaMinutes(null);
                delivery.setAssignSlaMinutes(null);
                delivery.setPickupSlaMinutes(null);
                delivery.setCancelledAt(null);
                delivery.setCancelReason(null);
                delivery.setCancelledBy(null);
                // failedAt and failureCode are kept for historical reporting

                deliveryRepo.save(delivery);

                ActorInfo actor = resolveActor(principal);
                String previousDriverName = "Inconnu";
                if (previousDriverId != null) {
                        DriverDTO prevDriver = transportPort.getDriver(previousDriverId.toString());
                        if (prevDriver != null) {
                                previousDriverName = prevDriver.getName() != null ? prevDriver.getName() : "Inconnu";
                        }
                }
                String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "Inconnu";

                Map<String, Object> auditDetails = Map.of(
                        "action", "replan",
                        "delivery", shortDeliveryId(delivery.getId()),
                        "client", clientName,
                        "previousDriver", previousDriverName,
                        "previousStatus", previousStatus.name(),
                        "reason", request.getNote() != null ? request.getNote() : ""
                );

                auditLogService.logAction(principal, "REPLAN_DELIVERY", "DELIVERY", delivery.getId().toString(), auditDetails);

                // Replan means pull the delivery out of the current execution route and return it to dispatch pool.
                removeStopFromCurrentRoute(delivery.getId());

                appendHistory(delivery,
                                DeliveryStatus.UNSCHEDULED,
                                actor.name(),
                                actor.role(),
                                buildReplanOpsNote(previousStatus, previousDriverName, request.getNote()));

                eventPublisher.publishDeliveryReplanned(delivery.getOrder(), delivery, previousDriverId);

                return mapActionResult(delivery, "WARNING", "REPLANNED", "Delivery sent back to waiting lane");
        }
    // ── Confirm return-to-origin ──────────────────────────────────────────────

    @Transactional
    public void confirmReturn(UUID deliveryId, String note, UserPrincipal principal) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (!Boolean.TRUE.equals(delivery.getReturnToOrigin())) {
            throw AppException.badRequest("Delivery does not have a pending return-to-origin");
        }

        delivery.setReturnToOrigin(false);
        delivery.setDriverId(null);
        // Parcel is back at depot — make available for re-dispatch unless order was cancelled
        Order order = delivery.getOrder();
        boolean orderCancelled = order != null && order.getStatus() == OrderStatus.CANCELLED;
        if (!orderCancelled) {
            delivery.setStatus(DeliveryStatus.UNSCHEDULED);
            delivery.setAssignedAt(null);
            delivery.setPickedUpAt(null);
        }
        deliveryRepo.save(delivery);

        ActorInfo actor = resolveActor(principal);
        String resolvedNote = StringUtils.hasText(note) ? note.trim() : "Return to origin confirmed by admin";
        appendHistory(delivery, delivery.getStatus(), actor.name(), actor.role(),
                "Return to origin confirmed — parcel received at depot. " + resolvedNote);

        auditLogService.logAction(principal, "CONFIRM_RETURN", "DELIVERY", deliveryId.toString(),
                Map.of("action", "return_confirmed", "orderCancelled", String.valueOf(orderCancelled)));
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Transactional
    public void cancelDelivery(UUID deliveryId, String reason) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (!List.of(DeliveryStatus.UNSCHEDULED, DeliveryStatus.SCHEDULED, DeliveryStatus.PICKED_UP).contains(delivery.getStatus())) {
            throw AppException.badRequest("Cannot cancel delivery in status " + delivery.getStatus());
        }

        Order order = delivery.getOrder();
        boolean wasPickedUp = delivery.getStatus() == DeliveryStatus.PICKED_UP;

        // Soft-cancel: preserve the delivery row and its full audit history
        delivery.setStatus(DeliveryStatus.CANCELLED);
        delivery.setCancelledAt(LocalDateTime.now());
        delivery.setCancelledBy(Role.ADMIN);
        delivery.setCancelReason(reason);
        if (wasPickedUp) {
            // Driver physically has the parcel — flag for return-to-origin flow
            delivery.setReturnToOrigin(true);
        }
        deliveryRepo.save(delivery);

        // Remove the associated route stop (soft-delete)
        routeStopRepository.findByDeliveryId(deliveryId).ifPresent(stop -> {
            Route route = stop.getRoute();
            if (route != null && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
                stop.setStatus(RouteStopStatus.REMOVED_CANCELLED);
                stop.setRemovedAt(LocalDateTime.now());
                stop.setRemovedReason(reason != null ? reason : "CANCELLED");
                stop.setRemovedBy("ADMIN");
                routeStopRepository.save(stop);
                routeWebSocketService.notifyDriver(route.getDriverId(), "STOP_REMOVED", route.getId(), route.getName());
            } else if (route != null && route.getStatus() == RouteStatus.DRAFT) {
                routeStopRepository.delete(stop);
            }
        });

        appendHistory(delivery, DeliveryStatus.CANCELLED, "ADMIN", Role.ADMIN,
                "Delivery cancelled" + (wasPickedUp ? " after pickup — return to origin required" : "") +
                (reason != null ? ": " + reason : ""));

        eventPublisher.publishDeliveryCancelled(order, delivery, delivery.getDriverId());

        // Standardize: preserve record for audit logs and avoid FK violations with the deliveries table.
        if (order != null) {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepo.save(order);
            if (order.getSource() == OrderSource.ODOO) {
                erpSyncService.syncOrderCancellation(order);
            }
        }
    }

    @Transactional
    public AdminDeliveryDetailResponse createBackorderDelivery(UUID deliveryId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        Order order = delivery.getOrder();
        if (order == null) throw AppException.badRequest("No order attached to this delivery");

        if (order.getOdooBackorderId() == null) {
            throw AppException.badRequest("No Odoo Backorder ID registered for this order.");
        }

        // We clone the order to create a new delivery task
        // By appending "-B1" etc, we bypass unique constraint locally, while OdooClient still knows to look up the original sale.order
        String originalErpId = order.getErpOrderId();
        String newErpId = originalErpId != null ? originalErpId + "-B" + System.currentTimeMillis() : null;

        // Calculate remaining items
        List<com.asm.delivery.entity.OrderItem> remainingItems = new ArrayList<>();
        int newTotalQuantity = 0;
        BigDecimal newTotalWeightKg = BigDecimal.ZERO;

        if (order.getItems() != null) {
            for (com.asm.delivery.entity.OrderItem item : order.getItems()) {
                int planned = item.getQuantity() != null ? item.getQuantity() : 0;
                int done = item.getQuantityDone() != null ? item.getQuantityDone() : 0;
                int remaining = Math.max(planned - done, 0);

                if (remaining > 0) {
                    com.asm.delivery.entity.OrderItem clonedItem = new com.asm.delivery.entity.OrderItem();
                    clonedItem.setId(item.getId());
                    clonedItem.setSku(item.getSku());
                    clonedItem.setName(item.getName());
                    clonedItem.setQuantity(remaining);
                    clonedItem.setQuantityDone(0);
                                        clonedItem.setUnitWeightKg(item.getUnitWeightKg());
                                        clonedItem.setUnitPrice(item.getUnitPrice());
                    remainingItems.add(clonedItem);
                    newTotalQuantity += remaining;

                                        BigDecimal unitWeight = item.getUnitWeightKg() != null ? item.getUnitWeightKg() : BigDecimal.ZERO;
                                        newTotalWeightKg = newTotalWeightKg.add(unitWeight.multiply(BigDecimal.valueOf(remaining)));
                }
            }
        }

        if (remainingItems.isEmpty()) {
            throw AppException.badRequest("No remaining items to backorder");
        }

        Order backorder = Order.builder()
                .source(order.getSource())
                .schemaVersion(order.getSchemaVersion())
                .clientId(order.getClientId())
                .clientName(order.getClientName())
                .clientPhone(order.getClientPhone())
                .clientEmail(order.getClientEmail())
                .erpOrderId(newErpId)
                .erpClientId(order.getErpClientId())
                .erpExternalRef(order.getErpExternalRef())
                .originName(order.getOriginName())
                .originAddress(order.getOriginAddress())
                .originCity(order.getOriginCity())
                .originPostalCode(order.getOriginPostalCode())
                .originCountryCode(order.getOriginCountryCode())
                .originContactName(order.getOriginContactName())
                .originContactPhone(order.getOriginContactPhone())
                .originContactEmail(order.getOriginContactEmail())
                .dropoffAddress(order.getDropoffAddress())
                .dropoffCity(order.getDropoffCity())
                .dropoffPostalCode(order.getDropoffPostalCode())
                .dropoffCountryCode(order.getDropoffCountryCode())
                .dropoffLat(order.getDropoffLat())
                .dropoffLng(order.getDropoffLng())
                .deliveryInstructions(order.getDeliveryInstructions())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .priority(order.getPriority())
                .status(OrderStatus.PENDING)
                .items(remainingItems)
                .totalQuantity(newTotalQuantity)
                .totalWeightKg(newTotalWeightKg)
                .odooSyncStatus(null) // Unsynced because we just created it
                .build();
                
        // Save the new Order
        backorder = orderRepo.save(backorder);

        // Delete Odoo Backorder ID from the original order because we processed it
        order.setOdooBackorderId(null);
        orderRepo.save(order);

        // Automatically create a Delivery task for this backorder
        Delivery newDelivery = Delivery.builder()
                .order(backorder)
                .status(DeliveryStatus.UNSCHEDULED)
                .createdAt(LocalDateTime.now())
                .build();
        deliveryRepo.save(newDelivery);

        appendHistory(newDelivery,
                DeliveryStatus.UNSCHEDULED,
                "SYSTEM",
                Role.SYSTEM,
                "Backorder created from partial delivery #" + shortDeliveryId(delivery.getId()) + ".");

        appendHistory(delivery,
                delivery.getStatus(),
                "SYSTEM",
                Role.SYSTEM,
                "Backorder delivery #" + shortDeliveryId(newDelivery.getId()) + " created for remaining items.");

        return dispatchService.getDeliveryDetail(delivery.getId()); 
    }
    private Map<UUID, RouteInfo> loadRouteInfoMap(List<Delivery> deliveries) {
        List<UUID> deliveryIds = deliveries.stream()
                .map(Delivery::getId)
                .filter(Objects::nonNull)
                .toList();

        if (deliveryIds.isEmpty()) {
            return Map.of();
        }

        return routeStopRepository.findAllByDeliveryIdInWithRoute(deliveryIds).stream()
                .filter(routeStop -> routeStop.getRoute() != null)
                .collect(Collectors.toMap(
                        com.asm.delivery.entity.RouteStop::getDeliveryId,
                        routeStop -> new RouteInfo(routeStop.getRoute().getId(), routeStop.getRoute().getName()),
                        (existing, replacement) -> existing
                ));
    }
        private record RouteInfo(UUID routeId, String routeName) {}

        private AdminOpsExceptionsResponse.ExceptionItem mapActionResult(Delivery delivery,
                                                                                                                                                  String severity,
                                                                                                                                                  String motif,
                                                                                                                                                  String comment) {
                Order order = delivery.getOrder();
                DriverDTO driver = delivery.getDriverId() != null ? transportPort.getDriver(delivery.getDriverId().toString()) : null;
                RouteInfo routeInfo = loadRouteInfoMap(List.of(delivery)).get(delivery.getId());
                String zoneName = null;
                if (order != null && order.getZoneId() != null) {
                        zoneName = zoneRepository.findById(order.getZoneId()).map(Zone::getName).orElse(null);
                }
                return AdminOpsExceptionsResponse.ExceptionItem.builder()
                                .deliveryId(delivery.getId())
                                .orderId(order != null ? order.getId() : null)
                                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                                .status(delivery.getStatus())
                                .failureCode(delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null)
                                .motif(motif)
                                .driverId(delivery.getDriverId())
                                .driverName(driver != null ? driver.getName() : null)
                                .clientName(order != null ? order.getClientName() : null)
                                .city(order != null ? order.getDropoffCity() : null)
                                .zoneName(zoneName)
                                .severity(severity)
                                .comment(comment)
                                .returnToOrigin(Boolean.TRUE.equals(delivery.getReturnToOrigin()))
                                .createdAt(delivery.getCreatedAt())
                                .updatedAt(delivery.getUpdatedAt())
                                .build();
        }
        private void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role, String note) {
                historyRepo.save(DeliveryStatusHistory.builder()
                                .deliveryId(delivery.getId())
                                .status(status)
                                .changedBy(changedBy)
                                .changedByRole(role)
                                .note(note)
                                .build());
        }
        private ActorInfo resolveActor(UserPrincipal principal) {
                if (principal == null) {
                        return new ActorInfo("SYSTEM", Role.SYSTEM);
                }

                String actorName = StringUtils.hasText(principal.getName())
                                ? principal.getName().trim()
                                : (StringUtils.hasText(principal.getUserId()) ? principal.getUserId().trim() : "SYSTEM");

                Role role;
                try {
                        role = StringUtils.hasText(principal.getRole())
                                        ? Role.valueOf(principal.getRole().trim().toUpperCase(Locale.ROOT))
                                        : Role.ADMIN;
                } catch (IllegalArgumentException ex) {
                        role = Role.ADMIN;
                }
                return new ActorInfo(actorName, role);
        }
        private String buildReassignOpsNote(DeliveryStatus previousStatus, String previousDriver, String newDriver, String note) {
                String message;
                if (previousStatus == DeliveryStatus.PICKED_UP) {
                        message = String.format("Livraison déjà ramassée réassignée avec confirmation de passation. Ancien chauffeur: %s. Nouveau chauffeur: %s.", previousDriver, newDriver);
                } else {
                        message = String.format("Livraison réassignée par le dispatch. Ancien chauffeur: %s. Nouveau chauffeur: %s.", previousDriver, newDriver);
                }
                return appendReason(message, note);
        }

        private String buildReplanOpsNote(DeliveryStatus previousStatus, String previousDriver, String note) {
                String message;
                if (previousStatus == DeliveryStatus.FAILED) {
                        message = String.format("Livraison en échec replanifiée pour une nouvelle tentative. Chauffeur précédent: %s.", previousDriver);
                } else if (previousStatus == DeliveryStatus.PARTIALLY_DELIVERED) {
                        message = String.format("Livraison partiellement livrée. Éléments restants replanifiés. Chauffeur précédent: %s.", previousDriver);
                } else {
                        message = String.format("Livraison remise en file de planification par le dispatch. Chauffeur précédent: %s.", previousDriver);
                }
                return appendReason(message, note);
        }

        private String appendReason(String message, String note) {
                if (!StringUtils.hasText(note)) {
                        return message;
                }
                return message + " Reason: " + note.trim();
        }

        private String shortDeliveryId(UUID deliveryId) {
                if (deliveryId == null) {
                        return "UNKNOWN";
                }
                String raw = deliveryId.toString();
                return raw.length() <= 8 ? raw : raw.substring(0, 8);
        }
        private void assertReassignAllowed(Delivery delivery) {
                if (!REASSIGN_ALLOWED_STATUSES.contains(delivery.getStatus())) {
                        throw AppException.badRequest("Reassign is allowed only for SCHEDULED or PICKED_UP deliveries");
                }
        }

        private void assertReplanAllowed(Delivery delivery) {
                if (!REPLAN_ALLOWED_STATUSES.contains(delivery.getStatus())) {
                        throw AppException.badRequest("Replan is allowed only for SCHEDULED, PICKED_UP, or FAILED deliveries");
                }
        }

        private void removeStopFromCurrentRoute(UUID deliveryId) {
                routeStopRepository.findByDeliveryId(deliveryId).ifPresent(stop -> {
                        UUID previousRouteId = stop.getRoute().getId();
                        Route route = stop.getRoute();
                        if (route != null && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
                                // Soft-delete on active routes — keep audit trail
                                stop.setStatus(RouteStopStatus.REMOVED_REPLANNED);
                                stop.setRemovedAt(LocalDateTime.now());
                                stop.setRemovedReason("REPLANNED");
                                stop.setRemovedBy("ADMIN");
                                routeStopRepository.save(stop);
                        } else {
                                routeStopRepository.delete(stop);
                        }
                        repackStopOrder(previousRouteId);
                });
        }

        private Set<UUID> moveStopToDriverRoute(Delivery delivery, UUID targetDriverId, String actorName, java.time.LocalTime requestedStartTime, java.time.LocalTime requestedEndTime) {
                Set<UUID> affectedRouteIds = new LinkedHashSet<>();
                routeStopRepository.findByDeliveryId(delivery.getId()).ifPresent(currentStop -> {
                        Route sourceRoute = currentStop.getRoute();
                        if (sourceRoute == null) {
                                return;
                        }

                        if (targetDriverId.equals(sourceRoute.getDriverId())) {
                                return;
                        }

                        UUID sourceRouteId = sourceRoute.getId();
                        affectedRouteIds.add(sourceRouteId);
                        routeStopRepository.delete(currentStop);
                        repackStopOrder(sourceRouteId);

                        Route targetRoute = findOrCreateRouteForDriver(targetDriverId, sourceRoute, actorName);
                        affectedRouteIds.add(targetRoute.getId());
                        List<RouteStop> targetStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(targetRoute.getId());
                        
                        // User-requested boundary validation
                        if (requestedStartTime != null && !targetStops.isEmpty()) {
                                RouteStop lastTargetStop = targetStops.get(targetStops.size() - 1);
                                java.time.LocalTime referenceTime = lastTargetStop.getEndTimeWindow() != null 
                                            ? lastTargetStop.getEndTimeWindow() 
                                            : (lastTargetStop.getEtaAt() != null ? lastTargetStop.getEtaAt().toLocalTime() : targetRoute.getPlannedStartTime());
                                
                                if (referenceTime != null && requestedStartTime.isBefore(referenceTime)) {
                                        throw AppException.badRequest("Reassigned stop start time (" + requestedStartTime + ") must be after current route's last stop time (" + referenceTime + ")");
                                }
                        }

                        // Determine final windows
                        java.time.LocalTime finalStartTime = requestedStartTime != null ? requestedStartTime : currentStop.getStartTimeWindow();
                        java.time.LocalTime finalEndTime = requestedEndTime != null ? requestedEndTime : currentStop.getEndTimeWindow();

                        // Extend Route boundaries if the newly placed window is outside Current bounds
                        boolean routeModified = false;
                        if (finalStartTime != null && finalStartTime.isBefore(targetRoute.getPlannedStartTime())) {
                                targetRoute.setPlannedStartTime(finalStartTime);
                                routeModified = true;
                        }
                        if (finalEndTime != null && finalEndTime.isAfter(targetRoute.getPlannedEndTime())) {
                                targetRoute.setPlannedEndTime(finalEndTime);
                                routeModified = true;
                        }
                        if (routeModified) {
                                routeRepository.save(targetRoute);
                        }

                        int nextOrder = targetStops.size() + 1;

                        routeStopRepository.save(RouteStop.builder()
                                        .route(targetRoute)
                                        .deliveryId(delivery.getId())
                                        .stopOrder(nextOrder)
                                        .status(RouteStopStatus.PENDING)
                                        .notes("Moved by dispatch via reassign")
                                        .startTimeWindow(finalStartTime)
                                        .endTimeWindow(finalEndTime)
                                        .bufferMinutes(currentStop.getBufferMinutes())
                                        .build());
                });
                return affectedRouteIds;
        }

        private Route findOrCreateRouteForDriver(UUID driverId, Route sourceRoute, String actorName) {
                List<RouteStatus> activeStatuses = List.of(RouteStatus.DRAFT, RouteStatus.VALIDATED, RouteStatus.IN_PROGRESS);
                List<Route> routes = routeRepository.findByDriverIdAndDateAndStatusIn(driverId, sourceRoute.getDate(), activeStatuses);
                if (!routes.isEmpty()) {
                        return routes.get(0);
                }

                String creator = StringUtils.hasText(actorName) ? actorName : "SYSTEM";
                // Inherit active status so the driver sees the new route immediately
                RouteStatus inheritedStatus = (sourceRoute.getStatus() == RouteStatus.VALIDATED
                        || sourceRoute.getStatus() == RouteStatus.IN_PROGRESS)
                        ? RouteStatus.VALIDATED : RouteStatus.DRAFT;
                Route draftRoute = Route.builder()
                                .name("Dispatch route " + sourceRoute.getDate() + " " + driverId.toString().substring(0, 8))
                                .driverId(driverId)
                                .vehicleId(null)
                                .date(sourceRoute.getDate())
                                .plannedStartTime(sourceRoute.getPlannedStartTime() != null ? sourceRoute.getPlannedStartTime() : LocalTime.of(8, 0))
                                .plannedEndTime(sourceRoute.getPlannedEndTime() != null ? sourceRoute.getPlannedEndTime() : LocalTime.of(18, 0))
                                .depotId(sourceRoute.getDepotId())
                                .departureTime(sourceRoute.getDepartureTime())
                                .city(sourceRoute.getCity())
                                .status(inheritedStatus)
                                .createdBy(creator)
                                .build();
                return routeRepository.save(draftRoute);
        }

        private void repackStopOrder(UUID routeId) {
                List<RouteStop> activeStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)
                        .stream()
                        .filter(s -> s.getStatus() != RouteStopStatus.REMOVED_REPLANNED
                                  && s.getStatus() != RouteStopStatus.REMOVED_CANCELLED)
                        .toList();
                for (int i = 0; i < activeStops.size(); i++) {
                        activeStops.get(i).setStopOrder(i + 1);
                }
                if (!activeStops.isEmpty()) {
                        routeStopRepository.saveAll(activeStops);
                }
        }
        private record ActorInfo(String name, Role role) {}


}
