package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Handoff;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.Route;
import com.asm.delivery.event.CloudEventWrapper;
import com.asm.delivery.event.DeliveryEventPayload;
import com.asm.delivery.event.HandoffEventPayload;
import com.asm.delivery.event.RouteEventPayload;
import com.asm.delivery.transport.TransportPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final SimpMessagingTemplate ws;

    @Autowired(required = false)
    private FcmNotificationService fcm;

    @Autowired
    private TransportPort transportPort;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.asm.delivery.repository.RouteStopRepository routeStopRepository;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getDriverName(UUID driverId) {
        if (driverId == null) return null;
        try {
            var d = transportPort.getDriver(driverId.toString());
            return d != null ? d.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private DeliveryEventPayload deliveryPayload(String event, Order order, Delivery delivery) {
        String erpRef = order != null ? order.getErpOrderId() : null;
        if (erpRef == null && order != null) {
            erpRef = order.getId().toString();
        }
        
        String routeId = null;
        String routeName = null;
        try {
            var activeStop = routeStopRepository.findActiveByDeliveryIdWithRoute(delivery.getId());
            if (activeStop.isPresent()) {
                var route = activeStop.get().getRoute();
                if (route != null) {
                    routeId = route.getId().toString();
                    routeName = route.getName();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        
        return DeliveryEventPayload.builder()
            .deliveryId(delivery.getId().toString())
            .erpOrderId(erpRef)
            .status(delivery.getStatus().name())
            .driverId(delivery.getDriverId() != null ? delivery.getDriverId().toString() : null)
            .driverName(getDriverName(delivery.getDriverId()))
            .clientName(order != null ? order.getClientName() : null)
            .clientPhone(order != null ? order.getClientPhone() : null)
            .dropoffAddress(order != null ? order.getDropoffAddress() : null)
            .dropoffLat(order != null ? order.getDropoffLat() : null)
            .dropoffLng(order != null ? order.getDropoffLng() : null)
            .totalAmount(order != null ? order.getTotalAmount() : null)
            .currency(order != null ? order.getCurrency() : null)
            .isCod(order != null ? order.getIsCod() : null)
            .items(order != null ? order.getItems() : null)
            .etaAt(delivery.getRouteEtaAt() != null ? delivery.getRouteEtaAt().toString() : null)
            .routeDistanceKm(delivery.getRouteDistanceKm())
            .routeDurationMinutes(delivery.getRouteDurationMinutes())
            .transitSlaMinutes(delivery.getTransitSlaMinutesComputed())
            .routeProvider(delivery.getRouteProvider())
            .routeId(routeId)
            .routeName(routeName)
            .build();
    }

    private RouteEventPayload routePayload(String event, Route route) {
        return RouteEventPayload.builder()
            .routeId(route.getId().toString())
            .routeName(route.getName())
            .status(route.getStatus().name())
            .driverId(route.getDriverId() != null ? route.getDriverId().toString() : null)
            .driverName(getDriverName(route.getDriverId()))
            .date(route.getDate())
            .plannedStartTime(route.getPlannedStartTime())
            .plannedEndTime(route.getPlannedEndTime())
            .stopCount(route.getStops() != null ? route.getStops().size() : 0)
            .build();
    }

    private void sendDelivery(String eventType, DeliveryEventPayload payload) {
        CloudEventWrapper<DeliveryEventPayload> envelope = CloudEventWrapper.<DeliveryEventPayload>builder()
            .source("/delivery-service")
            .type(eventType)
            .data(payload)
            .build();
        ws.convertAndSend("/topic/admin.deliveries", envelope);
    }

    private void sendRoute(String eventType, Object payload) {
        CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
            .source("/delivery-service")
            .type(eventType)
            .data(payload)
            .build();
        ws.convertAndSend("/topic/admin.routes", envelope);
    }

    private void sendErp(String eventType, Object payload) {
        CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
            .source("/delivery-service")
            .type(eventType)
            .data(payload)
            .build();
        ws.convertAndSend("/topic/admin.erp", envelope);
    }

    /** Push a real-time event to a single driver's personal STOMP topic. */
    private void sendDriver(UUID driverId, String eventType, Object payload) {
        if (driverId == null) return;
        CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
            .source("/delivery-service")
            .type(eventType)
            .data(payload)
            .build();
        ws.convertAndSend("/topic/driver." + driverId, envelope);
    }

    // ── Handoff lifecycle events (real-time: driver topics + admin + FCM) ───────

    private HandoffEventPayload handoffPayload(Handoff h, Order order) {
        return HandoffEventPayload.builder()
            .handoffId(h.getId().toString())
            .state(h.getState() != null ? h.getState().name() : null)
            .deliveryId(h.getDeliveryId() != null ? h.getDeliveryId().toString() : null)
            .routeId(h.getRouteId() != null ? h.getRouteId().toString() : null)
            .erpOrderId(order != null ? order.resolveRef() : null)
            .clientName(order != null ? order.getClientName() : null)
            .dropoffAddress(order != null ? order.getDropoffAddress() : null)
            .fromDriverId(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null)
            .fromDriverName(getDriverName(h.getFromDriverId()))
            .toDriverId(h.getToDriverId() != null ? h.getToDriverId().toString() : null)
            .toDriverName(getDriverName(h.getToDriverId()))
            .reason(h.getReason())
            .build();
    }

    /** A handoff was requested: tell the receiver (incoming), the sender (outgoing) and admins. */
    public void publishHandoffRequested(Handoff h, Order order) {
        executeAfterCommitAsync(() -> {
            HandoffEventPayload p = handoffPayload(h, order);
            log.info("EVENT handoff.requested handoffId={} from={} to={}", h.getId(), h.getFromDriverId(), h.getToDriverId());
            sendDriver(h.getToDriverId(), "handoff.incoming", p);
            sendDriver(h.getFromDriverId(), "handoff.outgoing", p);
            ws.convertAndSend("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("handoff.requested").data(p).build());
            sendFcmFatPayload(h.getToDriverId() != null ? h.getToDriverId().toString() : null, "HANDOFF_INCOMING", p);
            sendFcmFatPayload(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null, "HANDOFF_OUTGOING", p);
        });
    }

    /** The sender generated the one-time code: nudge the receiver to scan. */
    public void publishHandoffCodeReady(Handoff h, Order order) {
        executeAfterCommitAsync(() -> {
            HandoffEventPayload p = handoffPayload(h, order);
            sendDriver(h.getToDriverId(), "handoff.code_ready", p);
        });
    }

    /** Custody confirmed: tell both drivers and the admin dashboard. */
    public void publishHandoffConfirmed(Handoff h, Order order) {
        executeAfterCommitAsync(() -> {
            HandoffEventPayload p = handoffPayload(h, order);
            log.info("EVENT handoff.confirmed handoffId={} deliveryId={}", h.getId(), h.getDeliveryId());
            sendDriver(h.getToDriverId(), "handoff.confirmed", p);
            sendDriver(h.getFromDriverId(), "handoff.confirmed", p);
            ws.convertAndSend("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("delivery.handoff_confirmed").data(p).build());
            sendFcmFatPayload(h.getToDriverId() != null ? h.getToDriverId().toString() : null, "HANDOFF_CONFIRMED", p);
            sendFcmFatPayload(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null, "HANDOFF_CONFIRMED", p);
        });
    }

    /** Handoff aborted: tell both drivers and admins. */
    public void publishHandoffCancelled(Handoff h, Order order) {
        executeAfterCommitAsync(() -> {
            HandoffEventPayload p = handoffPayload(h, order);
            log.info("EVENT handoff.cancelled handoffId={} reason={}", h.getId(), h.getReason());
            sendDriver(h.getToDriverId(), "handoff.cancelled", p);
            sendDriver(h.getFromDriverId(), "handoff.cancelled", p);
            ws.convertAndSend("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("handoff.cancelled").data(p).build());
            sendFcmFatPayload(h.getToDriverId() != null ? h.getToDriverId().toString() : null, "HANDOFF_CANCELLED", p);
            sendFcmFatPayload(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null, "HANDOFF_CANCELLED", p);
        });
    }

    /** SLA overdue: escalate to admins and remind both drivers. */
    public void publishHandoffOverdue(Handoff h, Order order) {
        executeAfterCommitAsync(() -> {
            HandoffEventPayload p = handoffPayload(h, order);
            log.warn("EVENT handoff.overdue handoffId={} deliveryId={}", h.getId(), h.getDeliveryId());
            ws.convertAndSend("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("handoff.overdue").data(p).build());
            sendDriver(h.getToDriverId(), "handoff.incoming", p);
            sendDriver(h.getFromDriverId(), "handoff.outgoing", p);
        });
    }
    
    private void sendFcmFatPayload(String driverId, String eventType, Object payload) {
        if (fcm == null || driverId == null) return;
        try {
            CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
                .source("/delivery-service")
                .type(eventType)
                .data(payload)
                .build();
            String json = objectMapper.writeValueAsString(envelope);
            fcm.sendDataToDriver(driverId, Map.of("payload", json, "event_type", eventType));
        } catch (Exception e) {
            log.warn("Failed to serialize FCM payload for driverId={}: {}", driverId, e.getMessage());
        }
    }

    public void publishDriverLocation(UUID driverId, BigDecimal lat, BigDecimal lng) {
        Map<String, Object> p = new HashMap<>();
        p.put("driverId", driverId.toString());
        p.put("lat", lat);
        p.put("lng", lng);

        sendRoute("driver.location_updated", p);
    }

    public void publishPublicDriverLocation(UUID deliveryId, BigDecimal lat, BigDecimal lng) {
        Map<String, Object> p = new HashMap<>();
        p.put("lat", lat);
        p.put("lng", lng);
        
        CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
            .source("/delivery-service")
            .type("driver.location_updated")
            .data(p)
            .build();
        ws.convertAndSend("/topic/public." + deliveryId, envelope);
    }

    private void executeAfterCommitAsync(Runnable runnable) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        java.util.concurrent.CompletableFuture.runAsync(runnable);
                    }
                }
            );
        } else {
            java.util.concurrent.CompletableFuture.runAsync(runnable);
        }
    }

    public void publishSlaBreach(Delivery delivery, String motif, String severity, Map<String, Object> params) {
        // Eagerly resolve the payload in the active transaction thread to avoid LazyInitializationException
        final DeliveryEventPayload p = deliveryPayload("sla.breach", delivery.getOrder(), delivery);
        p.setMotif(motif);
        p.setSeverity(severity);
        p.setSlaParams(params != null ? params : Map.of());
        
        String elapsedStr = p.getSlaParams().get("elapsed") != null ? p.getSlaParams().get("elapsed") + " min" : "";
        String msg = p.getClientName() != null ? p.getClientName() + " — " : "";
        if ("SLA_WAITING".equals(motif)) msg += "Attente de " + elapsedStr + " (Dépassement SLA)";
        else if ("SLA_ASSIGNMENT".equals(motif)) msg += "Délai démarrage " + elapsedStr + " (Dépassement SLA)";
        else if ("SLA_PICKUP".equals(motif)) msg += "Délai de départ " + elapsedStr + " (Dépassement SLA)";
        else if ("SLA_TRANSIT".equals(motif)) msg += "En retard sur le trajet (Dépassement SLA)";
        else msg += "Dépassement SLA critique";
        
        p.setSlaMessage(msg);

        executeAfterCommitAsync(() -> {
            log.warn("EVENT sla.breach deliveryId={} motif={} severity={}", delivery.getId(), motif, severity);
            sendDelivery("sla.breach", p);
        });
    }

    // ── Delivery events ───────────────────────────────────────────────────────

    public void publishDeliveryCreated(Order order, Delivery delivery) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.created orderId={} deliveryId={}", order != null ? order.getId() : null, delivery.getId());
            sendDelivery("delivery.created", deliveryPayload("delivery.created", order, delivery));
        });
    }

    public void publishDeliveryScheduled(Order order, Delivery delivery, UUID driverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.scheduled orderId={} deliveryId={} driverId={}", order != null ? order.getId() : null, delivery.getId(), driverId);
            DeliveryEventPayload p = deliveryPayload("delivery.scheduled", order, delivery);
            p.setDriverId(driverId != null ? driverId.toString() : null);
            p.setDriverName(getDriverName(driverId));
            sendDelivery("delivery.scheduled", p);
            
            if (driverId != null) {
                sendFcmFatPayload(driverId.toString(), "DELIVERY_ASSIGNED", p);
            }
        });
    }

    public void publishDeliveryPickedUp(Order order, Delivery delivery) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.picked_up orderId={} deliveryId={}", order != null ? order.getId() : null, delivery.getId());
            sendDelivery("delivery.picked_up", deliveryPayload("delivery.picked_up", order, delivery));
        });
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery, BigDecimal lat, BigDecimal lng) {
        executeAfterCommitAsync(() -> {
            publishDeliveryInTransit(order, delivery, lat, lng, null, null, null, null, null);
        });
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery, BigDecimal lat, BigDecimal lng,
            BigDecimal routeDistanceKm, Integer routeDurationMinutes, Integer transitSlaMinutesComputed,
            LocalDateTime routeEtaAt, String routeProvider) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.in_transit orderId={} deliveryId={} lat={} lng={} eta={}", order != null ? order.getId() : null, delivery.getId(), lat, lng, routeEtaAt);
            DeliveryEventPayload p = deliveryPayload("delivery.in_transit", order, delivery);
            p.setLat(lat);
            p.setLng(lng);
            if (routeDistanceKm != null) p.setRouteDistanceKm(routeDistanceKm);
            if (routeDurationMinutes != null) p.setRouteDurationMinutes(routeDurationMinutes);
            if (transitSlaMinutesComputed != null) p.setTransitSlaMinutes(transitSlaMinutesComputed);
            if (routeEtaAt != null) p.setEtaAt(routeEtaAt.toString());
            if (routeProvider != null) p.setRouteProvider(routeProvider);
            sendDelivery("delivery.in_transit", p);
        });
    }

    public void publishDeliveryCompleted(Order order, Delivery delivery, UUID driverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.completed orderId={} deliveryId={} driverId={}", order != null ? order.getId() : null, delivery.getId(), driverId);
            sendDelivery("delivery.completed", deliveryPayload("delivery.completed", order, delivery));
        });
    }

    public void publishDeliveryFailed(Order order, Delivery delivery, String reason) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.failed orderId={} deliveryId={} reason={}", order != null ? order.getId() : null, delivery.getId(), reason);
            DeliveryEventPayload p = deliveryPayload("delivery.failed", order, delivery);
            p.setReason(reason);
            sendDelivery("delivery.failed", p);
        });
    }

    public void publishDeliveryCancelled(Order order, Delivery delivery, UUID driverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.cancelled orderId={} deliveryId={} driverId={}", order != null ? order.getId() : null, delivery.getId(), driverId);
            sendDelivery("delivery.cancelled", deliveryPayload("delivery.cancelled", order, delivery));
        });
    }

    public void publishDeliveryReassigned(Order order, Delivery delivery, UUID previousDriverId, UUID newDriverId) {
        publishDeliveryReassigned(order, delivery, previousDriverId, newDriverId, true);
    }

    /**
     * @param notifyDrivers when false, only the admin dashboard event is sent and the
     *        driver FCMs are suppressed — used when a custody handoff is being created,
     *        so the drivers receive accurate handoff prompts instead of the misleading
     *        "new delivery"/"removed" pushes.
     */
    public void publishDeliveryReassigned(Order order, Delivery delivery, UUID previousDriverId, UUID newDriverId, boolean notifyDrivers) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.reassigned orderId={} deliveryId={} previousDriverId={} newDriverId={} notifyDrivers={}", order != null ? order.getId() : null, delivery.getId(), previousDriverId, newDriverId, notifyDrivers);
            DeliveryEventPayload p = deliveryPayload("delivery.reassigned", order, delivery);
            p.setPreviousDriverId(previousDriverId != null ? previousDriverId.toString() : null);
            p.setNewDriverId(newDriverId != null ? newDriverId.toString() : null);
            sendDelivery("delivery.reassigned", p);

            if (!notifyDrivers) return;
            if (newDriverId != null) {
                sendFcmFatPayload(newDriverId.toString(), "DELIVERY_ASSIGNED", p);
            }
            if (previousDriverId != null) {
                sendFcmFatPayload(previousDriverId.toString(), "DELIVERY_REMOVED", p);
            }
        });
    }

    public void publishDeliveryReplanned(Order order, Delivery delivery, UUID previousDriverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.replanned orderId={} deliveryId={} previousDriverId={}", order != null ? order.getId() : null, delivery.getId(), previousDriverId);
            DeliveryEventPayload p = deliveryPayload("delivery.replanned", order, delivery);
            p.setPreviousDriverId(previousDriverId != null ? previousDriverId.toString() : null);
            sendDelivery("delivery.replanned", p);
        });
    }

    public void publishDeliveryReassignedAway(Order order, Delivery delivery, UUID previousDriverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.reassigned_away deliveryId={} previousDriverId={}", delivery.getId(), previousDriverId);
            DeliveryEventPayload p = deliveryPayload("delivery.reassigned_away", order, delivery);
            p.setPreviousDriverId(previousDriverId != null ? previousDriverId.toString() : null);
            sendDelivery("delivery.reassigned_away", p);
            
            if (previousDriverId != null) {
                sendFcmFatPayload(previousDriverId.toString(), "DELIVERY_REMOVED", p);
            }
        });
    }

    public void publishHandoffRequired(Order order, Delivery delivery, UUID newDriverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.handoff_required deliveryId={} newDriverId={}", delivery.getId(), newDriverId);
            DeliveryEventPayload p = deliveryPayload("delivery.handoff_required", order, delivery);
            p.setNewDriverId(newDriverId != null ? newDriverId.toString() : null);
            sendDelivery("delivery.handoff_required", p);
            
            if (newDriverId != null) {
                sendFcmFatPayload(newDriverId.toString(), "HANDOFF_REQUIRED", p);
            }
        });
    }

    public void publishErpOrdersReady(int count) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT erp.orders_ready count={}", count);
            Map<String, Object> m = new HashMap<>();
            m.put("count", count);
            CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
                .source("/delivery-service")
                .type("erp.orders_ready")
                .data(m)
                .build();
            ws.convertAndSend("/topic/admin.erp", envelope);
        });
    }

    /**
     * Notifies admins/dispatchers that an order failed to sync to the ERP after
     * all retries were exhausted (permanent dead-letter). {@code operation} is a
     * stable code (STOCK / CANCELLATION / FAILURE_REPORT) the UI localizes.
     * Field reads happen synchronously while the caller's transaction is still
     * open; only the WebSocket publish is deferred to after commit.
     */
    public void publishErpSyncFailed(Order order, UUID deliveryId, String operation) {
        if (order == null) return;
        final String dId = deliveryId != null ? deliveryId.toString() : order.getId().toString();
        final String erpId = order.getErpOrderId();
        final String client = order.getClientName();
        executeAfterCommitAsync(() -> {
            log.error("EVENT erp.sync_failed deliveryId={} erpOrderId={} operation={}", dId, erpId, operation);
            DeliveryEventPayload p = DeliveryEventPayload.builder()
                    .deliveryId(dId)
                    .erpOrderId(erpId)
                    .clientName(client)
                    .motif(operation)
                    .build();
            sendErp("erp.sync_failed", p);
        });
    }

    // ── Route events ──────────────────────────────────────────────────────────

    public void publishRouteValidated(Route route) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.validated routeId={} driverId={}", route.getId(), route.getDriverId());
            RouteEventPayload p = routePayload("route.validated", route);
            sendRoute("route.validated", p);
            
            if (route.getDriverId() != null) {
                sendFcmFatPayload(route.getDriverId().toString(), "ROUTE_VALIDATED", p);
            }
        });
    }

    public void publishRouteScheduleChanged(Route route) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.schedule_changed routeId={} driverId={}", route.getId(), route.getDriverId());
            RouteEventPayload p = routePayload("route.schedule_changed", route);
            sendRoute("route.schedule_changed", p);
            
            if (route.getDriverId() != null) {
                sendFcmFatPayload(route.getDriverId().toString(), "ROUTE_SCHEDULE_CHANGED", p);
            }
        });
    }

    public void publishRouteStopAdded(Route route, String clientName) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.stop_added routeId={} driverId={} client={}", route.getId(), route.getDriverId(), clientName);
            RouteEventPayload p = routePayload("route.stop_added", route);
            p.setClientName(clientName);
            sendRoute("route.stop_added", p);
            
            if (route.getDriverId() != null) {
                sendFcmFatPayload(route.getDriverId().toString(), "ROUTE_STOP_ADDED", p);
            }
        });
    }

    public void publishRouteStopRemoved(Route route, String clientName) {
        executeAfterCommitAsync(() -> {
            publishRouteStopRemoved(route, clientName, null, null);
        });
    }

    public void publishRouteStopRemoved(Route route, String clientName, String erpOrderId, String reason) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.stop_removed routeId={} driverId={} client={}", route.getId(), route.getDriverId(), clientName);
            RouteEventPayload p = routePayload("route.stop_removed", route);
            p.setClientName(clientName);
            p.setErpOrderId(erpOrderId);
            p.setReason(reason);
            sendRoute("route.stop_removed", p);
            
            if (route.getDriverId() != null) {
                sendFcmFatPayload(route.getDriverId().toString(), "ROUTE_STOP_REMOVED", p);
            }
        });
    }

    public void publishStopsTransferred(UUID sourceDriverId, UUID targetDriverId, int count, boolean requiresHandoff) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT stops.transferred sourceDriver={} targetDriver={} count={} handoff={}", sourceDriverId, targetDriverId, count, requiresHandoff);
            if (fcm == null) return;
            Map<String, Object> p = Map.of("count", count);
            if (sourceDriverId != null) {
                sendFcmFatPayload(sourceDriverId.toString(), "STOPS_TRANSFERRED_OUT", p);
            }
            if (targetDriverId != null) {
                sendFcmFatPayload(targetDriverId.toString(), requiresHandoff ? "HANDOFF_REQUIRED" : "STOPS_TRANSFERRED_IN", p);
            }
        });
    }

    public void publishHandoffConfirmed(UUID deliveryId, UUID routeId, UUID driverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.handoff_confirmed deliveryId={} routeId={} driverId={}", deliveryId, routeId, driverId);
            Map<String, Object> m = new HashMap<>();
            m.put("deliveryId", deliveryId);
            m.put("routeId", routeId);
            m.put("driverId", driverId);
            
            CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
                .source("/delivery-service")
                .type("delivery.handoff_confirmed")
                .data(m)
                .build();
            ws.convertAndSend("/topic/admin.routes", envelope);
        });
    }
}
