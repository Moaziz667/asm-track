package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Handoff;
import com.asm.delivery.entity.Notification;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.event.CloudEventWrapper;
import com.asm.delivery.event.DeliveryEventPayload;
import com.asm.delivery.event.HandoffEventPayload;
import com.asm.delivery.event.RouteEventPayload;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private com.asm.delivery.notification.NotificationGateway notificationGateway;

    @Autowired
    private TransportPort transportPort;

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
        notificationGateway.broadcast("/topic/admin.deliveries", envelope);
    }

    private void sendRoute(String eventType, Object payload) {
        CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
            .source("/delivery-service")
            .type(eventType)
            .data(payload)
            .build();
        notificationGateway.broadcast("/topic/admin.routes", envelope);
    }

    private void sendErp(String eventType, Object payload) {
        CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
            .source("/delivery-service")
            .type(eventType)
            .data(payload)
            .build();
        notificationGateway.broadcast("/topic/admin.erp", envelope);
    }

    /** Push a real-time event to a single driver's personal STOMP topic. */
    private void sendDriver(UUID driverId, String eventType, Object payload) {
        if (driverId == null) return;
        CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
            .source("/delivery-service")
            .type(eventType)
            .data(payload)
            .build();
        notificationGateway.broadcast("/topic/driver." + driverId, envelope);
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
        final HandoffEventPayload p = handoffPayload(h, order);
        executeAfterCommitAsync(() -> {
            log.info("EVENT handoff.requested handoffId={} from={} to={}", h.getId(), h.getFromDriverId(), h.getToDriverId());
            sendDriver(h.getToDriverId(), "handoff.incoming", p);
            sendDriver(h.getFromDriverId(), "handoff.outgoing", p);
            notificationGateway.broadcast("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("handoff.requested").data(p).build());
            
            String clientName = order != null ? order.getClientName() : null;
            String fromName = p.getFromDriverName();
            String toName = p.getToDriverName();
            String msg = (clientName != null ? clientName : "Client") + " — passation "
                    + (fromName != null ? fromName : "—") + " → " + (toName != null ? toName : "—");

            notificationGateway.record(Notification.builder()
                    .eventType("handoff.requested").severity("warning")
                    .title("Passation requise")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(toName).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
            
            sendFcmFatPayload(h.getToDriverId() != null ? h.getToDriverId().toString() : null, "HANDOFF_INCOMING", p);
            sendFcmFatPayload(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null, "HANDOFF_OUTGOING", p);
        });
    }

    /** The sender generated the one-time code: nudge the receiver to scan. */
    public void publishHandoffCodeReady(Handoff h, Order order) {
        final HandoffEventPayload p = handoffPayload(h, order);
        executeAfterCommitAsync(() -> {
            sendDriver(h.getToDriverId(), "handoff.code_ready", p);
        });
    }

    /** Custody confirmed: tell both drivers and the admin dashboard. */
    public void publishHandoffConfirmed(Handoff h, Order order) {
        final HandoffEventPayload p = handoffPayload(h, order);
        executeAfterCommitAsync(() -> {
            log.info("EVENT handoff.confirmed handoffId={} deliveryId={}", h.getId(), h.getDeliveryId());
            sendDriver(h.getToDriverId(), "handoff.confirmed", p);
            sendDriver(h.getFromDriverId(), "handoff.confirmed", p);
            notificationGateway.broadcast("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("delivery.handoff_confirmed").data(p).build());
            
            String clientName = order != null ? order.getClientName() : null;
            String msg = "Colis" + (clientName != null ? " de " + clientName : "") + " remis au nouveau livreur";

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.handoff_confirmed").severity("info")
                    .title("Transfert colis confirmé")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getToDriverName()).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
            
            sendFcmFatPayload(h.getToDriverId() != null ? h.getToDriverId().toString() : null, "HANDOFF_CONFIRMED", p);
            sendFcmFatPayload(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null, "HANDOFF_CONFIRMED", p);
        });
    }

    /** Handoff aborted: tell both drivers and admins. */
    public void publishHandoffCancelled(Handoff h, Order order) {
        final HandoffEventPayload p = handoffPayload(h, order);
        executeAfterCommitAsync(() -> {
            log.info("EVENT handoff.cancelled handoffId={} reason={}", h.getId(), h.getReason());
            sendDriver(h.getToDriverId(), "handoff.cancelled", p);
            sendDriver(h.getFromDriverId(), "handoff.cancelled", p);
            notificationGateway.broadcast("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("handoff.cancelled").data(p).build());
            
            String clientName = order != null ? order.getClientName() : null;
            String msg = (clientName != null ? clientName : "Client") + " — passation annulée"
                    + (h.getReason() != null && !h.getReason().isBlank() ? " · " + h.getReason() : "");

            notificationGateway.record(Notification.builder()
                    .eventType("handoff.cancelled").severity("warning")
                    .title("Passation annulée")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getToDriverName()).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
            
            sendFcmFatPayload(h.getToDriverId() != null ? h.getToDriverId().toString() : null, "HANDOFF_CANCELLED", p);
            sendFcmFatPayload(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null, "HANDOFF_CANCELLED", p);
        });
    }

    /** SLA overdue: escalate to admins and remind both drivers. */
    public void publishHandoffOverdue(Handoff h, Order order) {
        final HandoffEventPayload p = handoffPayload(h, order);
        executeAfterCommitAsync(() -> {
            log.warn("EVENT handoff.overdue handoffId={} deliveryId={}", h.getId(), h.getDeliveryId());
            notificationGateway.broadcast("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("handoff.overdue").data(p).build());
            
            String clientName = order != null ? order.getClientName() : null;
            String fromName = p.getFromDriverName();
            String toName = p.getToDriverName();
            String msg = (clientName != null ? clientName : "Client") + " — passation non confirmée ("
                    + (fromName != null ? fromName : "—") + " → " + (toName != null ? toName : "—") + ")";

            notificationGateway.record(Notification.builder()
                    .eventType("handoff.overdue").severity("critical")
                    .title("Passation en retard")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(toName).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
            
            sendDriver(h.getToDriverId(), "handoff.incoming", p);
            sendDriver(h.getFromDriverId(), "handoff.outgoing", p);
        });
    }
    
    /**
     * A depot PICKUP stop is overdue (route in progress, not yet confirmed past the threshold).
     * Real-time banner to the driver + Dispatch Desk, plus an FCM push that deep-links to the route.
     */
    public void publishPickupOverdue(Route route, RouteStop pickupStop, String depotName, int parcelCount) {
        executeAfterCommitAsync(() -> {
            Map<String, Object> p = new HashMap<>();
            p.put("routeId", route.getId().toString());
            p.put("routeName", route.getName());
            p.put("stopId", pickupStop.getId().toString());
            p.put("clientName", depotName != null ? depotName : "");
            p.put("reason", parcelCount + " colis");
            log.warn("EVENT pickup.overdue routeId={} stopId={} depot={}", route.getId(), pickupStop.getId(), depotName);
            sendDriver(route.getDriverId(), "pickup.overdue", p);
            notificationGateway.broadcast("/topic/admin.routes", CloudEventWrapper.builder()
                .source("/delivery-service").type("pickup.overdue").data(p).build());
            sendFcmFatPayload(route.getDriverId() != null ? route.getDriverId().toString() : null, "PICKUP_OVERDUE", p);
            // Persist so admins still see overdue depot pickups in the bell/history after a reload.
            String driverName = getDriverName(route.getDriverId());
            Map<String, Object> persisted = new HashMap<>();
            persisted.put("routeName", route.getName() != null ? route.getName() : "");
            persisted.put("clientName", depotName != null ? depotName : "");
            persisted.put("driverName", driverName != null ? driverName : "");
            persisted.put("reason", parcelCount + " colis");
            notificationGateway.record(Notification.builder()
                    .eventType("pickup.overdue").severity("critical")
                    .title("Pickup overdue")
                    .message((depotName != null ? depotName + " — " : "") + "pickup overdue ("
                            + parcelCount + " colis)")
                    .routeId(route.getId().toString())
                    .driverId(route.getDriverId() != null ? route.getDriverId().toString() : null)
                    .driverName(driverName).clientName(depotName)
                    .payload(persisted)
                    .build());
        });
    }

    private void sendFcmFatPayload(String driverId, String eventType, Object payload) {
        notificationGateway.pushToDriver(driverId, eventType, payload);
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
        notificationGateway.broadcast("/topic/public." + deliveryId, envelope);
    }

    private void executeAfterCommitAsync(Runnable runnable) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runAsyncLogged(runnable);
                    }
                }
            );
        } else {
            runAsyncLogged(runnable);
        }
    }

    /**
     * Runs the publish task off the request thread. {@link java.util.concurrent.CompletableFuture#runAsync}
     * discards exceptions silently, which previously hid failures (e.g. a LazyInitializationException while
     * building a payload) and made events vanish — so we log them here instead.
     */
    private void runAsyncLogged(Runnable runnable) {
        java.util.concurrent.CompletableFuture.runAsync(runnable)
            .exceptionally(ex -> {
                log.error("Async after-commit event publish failed: {}", ex.getMessage(), ex);
                return null;
            });
    }

    /**
     * Unified SLA notification (replaces the 4 {@code sla.breach} motifs). Fired only on health
     * transitions by {@link com.asm.delivery.sla.SlaStateService}. Carries phase/health/reasonKey so
     * the frontend renders one consistent message; payload resolved eagerly (avoids lazy-init).
     */
    public void publishSlaAlert(Delivery delivery, com.asm.delivery.sla.SlaPhase phase,
                                com.asm.delivery.sla.SlaHealth health, String reasonKey,
                                Map<String, String> reasonParams, java.time.LocalDateTime dueAt) {
        final DeliveryEventPayload p = deliveryPayload("sla.alert", delivery.getOrder(), delivery);
        final boolean critical = health == com.asm.delivery.sla.SlaHealth.BREACHED;
        p.setMotif(phase.name());
        p.setSeverity(critical ? "critical" : "warning");
        Map<String, Object> params = new HashMap<>();
        if (reasonParams != null) params.putAll(reasonParams);
        params.put("phase", phase.name());
        params.put("health", health.name());
        params.put("reasonKey", reasonKey);
        if (dueAt != null) params.put("dueAt", dueAt.toString());
        p.setSlaParams(params);
        p.setSlaMessage(reasonKey);

        executeAfterCommitAsync(() -> {
            log.info("EVENT sla.alert deliveryId={} phase={} health={}", delivery.getId(), phase, health);
            sendDelivery("sla.alert", p);
            notificationGateway.record(Notification.builder()
                    .eventType("sla.alert").severity(critical ? "critical" : "warning")
                    .title("SLA " + health.name())
                    .message(reasonKey)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(p.getClientName())
                    .payload(params)
                    .build());
        });
    }

    // ── Delivery events ───────────────────────────────────────────────────────

    public void publishDeliveryCreated(Order order, Delivery delivery) {
        final DeliveryEventPayload p = deliveryPayload("delivery.created", order, delivery);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.created orderId={} deliveryId={}", order != null ? order.getId() : null, delivery.getId());
            sendDelivery("delivery.created", p);
            
            String erpOrderId = order != null ? order.getErpOrderId() : null;
            String clientName = order != null ? order.getClientName() : null;
            String msg = erpOrderId != null
                    ? "Commande " + erpOrderId + " créée" + (clientName != null ? " · " + clientName : "")
                    : "Nouvelle commande" + (clientName != null ? " · " + clientName : "");
                    
            notificationGateway.record(Notification.builder()
                    .eventType("delivery.created").severity("info")
                    .title("Nouvelle livraison")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(p.getClientName())
                    .payload(new HashMap<>(Map.of()))
                    .build());
        });
    }

    public void publishDeliveryScheduled(Order order, Delivery delivery, UUID driverId) {
        final DeliveryEventPayload p = deliveryPayload("delivery.scheduled", order, delivery);
        p.setDriverId(driverId != null ? driverId.toString() : null);
        p.setDriverName(getDriverName(driverId));
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.scheduled orderId={} deliveryId={} driverId={}", order != null ? order.getId() : null, delivery.getId(), driverId);
            sendDelivery("delivery.scheduled", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String driverName = p.getDriverName();
            String msg = (clientName != null ? clientName : "Client") + " — planifiée"
                    + (driverName != null ? " · " + driverName : "");

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.scheduled").severity("info")
                    .title("Livraison planifiée")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(driverName).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
            
            if (driverId != null) {
                sendFcmFatPayload(driverId.toString(), "DELIVERY_ASSIGNED", p);
            }
        });
    }

    public void publishDeliveryPickedUp(Order order, Delivery delivery) {
        final DeliveryEventPayload p = deliveryPayload("delivery.picked_up", order, delivery);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.picked_up orderId={} deliveryId={}", order != null ? order.getId() : null, delivery.getId());
            sendDelivery("delivery.picked_up", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String msg = (clientName != null ? clientName : "Client") + " — pris en charge";

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.picked_up").severity("info")
                    .title("Colis récupéré")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
        });
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery, BigDecimal lat, BigDecimal lng) {
        publishDeliveryInTransit(order, delivery, lat, lng, null, null, null, null, null);
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery, BigDecimal lat, BigDecimal lng,
            BigDecimal routeDistanceKm, Integer routeDurationMinutes, Integer transitSlaMinutesComputed,
            LocalDateTime routeEtaAt, String routeProvider) {
        final DeliveryEventPayload p = deliveryPayload("delivery.in_transit", order, delivery);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.in_transit orderId={} deliveryId={} lat={} lng={} eta={}", order != null ? order.getId() : null, delivery.getId(), lat, lng, routeEtaAt);
            p.setLat(lat);
            p.setLng(lng);
            if (routeDistanceKm != null) p.setRouteDistanceKm(routeDistanceKm);
            if (routeDurationMinutes != null) p.setRouteDurationMinutes(routeDurationMinutes);
            if (transitSlaMinutesComputed != null) p.setTransitSlaMinutes(transitSlaMinutesComputed);
            if (routeEtaAt != null) p.setEtaAt(routeEtaAt.toString());
            if (routeProvider != null) p.setRouteProvider(routeProvider);
            sendDelivery("delivery.in_transit", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String driverName = p.getDriverName();
            String msg = (clientName != null ? clientName : "Client") + " — en route"
                    + (driverName != null ? " · " + driverName : "");

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.in_transit").severity("info")
                    .title("En livraison")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(driverName).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
        });
    }

    public void publishDeliveryCompleted(Order order, Delivery delivery, UUID driverId) {
        final DeliveryEventPayload p = deliveryPayload("delivery.completed", order, delivery);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.completed orderId={} deliveryId={} driverId={}", order != null ? order.getId() : null, delivery.getId(), driverId);
            sendDelivery("delivery.completed", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String msg = (clientName != null ? clientName : "Client") + " — livrée";

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.completed").severity("info")
                    .title("Livraison réussie")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
        });
    }

    public void publishDeliveryFailed(Order order, Delivery delivery, String reason) {
        final DeliveryEventPayload p = deliveryPayload("delivery.failed", order, delivery);
        // motif = the failure CODE only (CLIENT_ABSENT, REFUSED…); the front translates it via
        // `failureCodes`. reason = the driver's free-text comment only — never the collapsed
        // "LABEL — comment" string (that one lives on delivery.failReason for ERP/audit), so the UI
        // shows a clean, localized label instead of a raw enum.
        p.setMotif(delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null);
        p.setReason(reason != null && !reason.isBlank() ? reason.trim() : null);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.failed orderId={} deliveryId={} code={}", order != null ? order.getId() : null, delivery.getId(), p.getMotif());
            sendDelivery("delivery.failed", p);

            String clientName = order != null ? order.getClientName() : null;
            // Backend message is a localized-FR FALLBACK only; the front renders a translated template
            // from (motif, reason). Keep it human — no enum code, no debug text.
            String detail = p.getReason();
            String msg = (clientName != null ? clientName : "Client") + " — échouée"
                    + (detail != null ? " · " + detail : "");

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.failed").severity("critical")
                    .title("Échec livraison")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
        });
    }

    public void publishDeliveryCancelled(Order order, Delivery delivery, UUID driverId) {
        final DeliveryEventPayload p = deliveryPayload("delivery.cancelled", order, delivery);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.cancelled orderId={} deliveryId={} driverId={}", order != null ? order.getId() : null, delivery.getId(), driverId);
            sendDelivery("delivery.cancelled", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String msg = (clientName != null ? clientName : "Client") + " — annulée";

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.cancelled").severity("warning")
                    .title("Livraison annulée")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
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
        final DeliveryEventPayload p = deliveryPayload("delivery.reassigned", order, delivery);
        p.setPreviousDriverId(previousDriverId != null ? previousDriverId.toString() : null);
        p.setNewDriverId(newDriverId != null ? newDriverId.toString() : null);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.reassigned orderId={} deliveryId={} previousDriverId={} newDriverId={} notifyDrivers={}", order != null ? order.getId() : null, delivery.getId(), previousDriverId, newDriverId, notifyDrivers);
            sendDelivery("delivery.reassigned", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String driverName = p.getDriverName();
            String msg = (clientName != null ? clientName : "Client") + " — nouveau livreur"
                    + (driverName != null ? " · " + driverName : "");

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.reassigned").severity("info")
                    .title("Livraison réassignée")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(driverName).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());

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
        final DeliveryEventPayload p = deliveryPayload("delivery.replanned", order, delivery);
        p.setPreviousDriverId(previousDriverId != null ? previousDriverId.toString() : null);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.replanned orderId={} deliveryId={} previousDriverId={}", order != null ? order.getId() : null, delivery.getId(), previousDriverId);
            sendDelivery("delivery.replanned", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String msg = (clientName != null ? clientName : "Client") + " — reportée";

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.replanned").severity("warning")
                    .title("Livraison replanifiée")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(p.getClientName())
                    .payload(new HashMap<>(Map.of()))
                    .build());
        });
    }

    public void publishDeliveryReassignedAway(Order order, Delivery delivery, UUID previousDriverId) {
        final DeliveryEventPayload p = deliveryPayload("delivery.reassigned_away", order, delivery);
        p.setPreviousDriverId(previousDriverId != null ? previousDriverId.toString() : null);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.reassigned_away deliveryId={} previousDriverId={}", delivery.getId(), previousDriverId);
            sendDelivery("delivery.reassigned_away", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String msg = (clientName != null ? clientName : "Client") + " — retirée de la tournée du livreur";

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.reassigned_away").severity("warning")
                    .title("Livraison retirée")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(p.getDriverName()).clientName(p.getClientName())
                    .payload(new HashMap<>(Map.of()))
                    .build());
            
            if (previousDriverId != null) {
                sendFcmFatPayload(previousDriverId.toString(), "DELIVERY_REMOVED", p);
            }
        });
    }

    public void publishHandoffRequired(Order order, Delivery delivery, UUID newDriverId) {
        final DeliveryEventPayload p = deliveryPayload("delivery.handoff_required", order, delivery);
        p.setNewDriverId(newDriverId != null ? newDriverId.toString() : null);
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.handoff_required deliveryId={} newDriverId={}", delivery.getId(), newDriverId);
            sendDelivery("delivery.handoff_required", p);
            
            String clientName = order != null ? order.getClientName() : null;
            String driverName = getDriverName(newDriverId);
            String msg = (clientName != null ? clientName : "Client") + " — passation de colis requise"
                    + (driverName != null ? " · " + driverName : "");

            notificationGateway.record(Notification.builder()
                    .eventType("delivery.handoff_required").severity("warning")
                    .title("Passation requise")
                    .message(msg)
                    .orderRef(p.getErpOrderId()).deliveryId(p.getDeliveryId())
                    .routeId(p.getRouteId()).driverName(driverName).clientName(clientName)
                    .payload(new HashMap<>(Map.of()))
                    .build());
            
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
            notificationGateway.broadcast("/topic/admin.erp", envelope);
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
            notificationGateway.record(Notification.builder()
                    .eventType("erp.sync_failed").severity("critical")
                    .title("ERP sync failed")
                    .message((client != null ? client + " — " : "") + "Order " + (erpId != null ? erpId : dId)
                            + " could not sync to the ERP (" + (operation != null ? operation : "SYNC") + ")")
                    .orderRef(erpId).deliveryId(dId).clientName(client)
                    .payload(new HashMap<>(Map.of("operation", operation != null ? operation : "SYNC")))
                    .build());
        });
    }

    /**
     * V2 — Odoo changed an order whose delivery had already departed (PICKED_UP+). ASM kept the field
     * reality and did NOT apply the change; this alert tells a dispatcher to resolve it manually
     * (e.g. credit note, return) since the two systems intentionally diverge for this case.
     */
    public void publishErpConflict(Order order, UUID deliveryId, String changeType) {
        if (order == null) return;
        final String dId = deliveryId != null ? deliveryId.toString() : order.getId().toString();
        final String erpId = order.getErpOrderId();
        final String client = order.getClientName();
        executeAfterCommitAsync(() -> {
            log.warn("EVENT erp.conflict deliveryId={} erpOrderId={} changeType={}", dId, erpId, changeType);
            DeliveryEventPayload p = DeliveryEventPayload.builder()
                    .deliveryId(dId).erpOrderId(erpId).clientName(client).motif(changeType).build();
            sendErp("erp.conflict", p);
            notificationGateway.record(Notification.builder()
                    .eventType("erp.conflict").severity("warning")
                    .title("Conflit Odoo — livraison déjà partie")
                    .message((client != null ? client + " — " : "") + "Commande " + (erpId != null ? erpId : dId)
                            + " modifiée dans Odoo (" + (changeType != null ? changeType : "CHANGE")
                            + ") alors que la livraison est déjà partie. À traiter manuellement.")
                    .orderRef(erpId).deliveryId(dId).clientName(client)
                    .payload(new HashMap<>(Map.of("changeType", changeType != null ? changeType : "CHANGE")))
                    .build());
        });
    }

    /**
     * Refused-defect re-delivery: the customer refused goods for a defect (damaged / wrong item /
     * postponed) but still wants the product, so a replacement shipment was created. Emits a SINGLE
     * clear notification (instead of a confusing failed + backorder pair) — the failed visit already
     * has its own delivery.failed; this one is the "re-delivery scheduled" follow-up.
     */
    public void publishRedeliveryScheduled(Order order, UUID replacementDeliveryId) {
        if (order == null) return;
        final String orderRef = order.resolveRef();
        final String client = order.getClientName();
        final String rid = replacementDeliveryId != null ? replacementDeliveryId.toString() : null;
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.redelivery_scheduled orderRef={} replacementDeliveryId={}", orderRef, rid);
            Map<String, Object> p = new HashMap<>();
            p.put("deliveryId", rid);
            p.put("erpOrderId", orderRef);
            p.put("clientName", client);
            CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
                    .source("/delivery-service")
                    .type("delivery.redelivery_scheduled")
                    .data(p)
                    .build();
            notificationGateway.broadcast("/topic/admin.deliveries", envelope);
            Map<String, Object> persisted = new HashMap<>();
            persisted.put("erpOrderId", orderRef != null ? orderRef : "");
            persisted.put("clientName", client != null ? client : "");
            notificationGateway.record(Notification.builder()
                    .eventType("delivery.redelivery_scheduled").severity("warning")
                    .title("Re-livraison programmée")
                    .message((client != null ? client + " — " : "") + "refusé (défaut) — re-livraison programmée")
                    .orderRef(orderRef).deliveryId(rid).clientName(client)
                    .payload(persisted)
                    .build());
        });
    }

    // ── Return (RMA) events ─────────────────────────────────────────────────────

    /**
     * A return changed status (created, approved, received, restocked, rejected, cancelled — from admin or
     * the public self-service page). Broadcast on the already-subscribed admin.deliveries topic so the
     * Returns page invalidates without a new STOMP topic. Fire-and-forget nudge; no persisted notification.
     */
    public void publishRmaStatusChanged(com.asm.delivery.entity.Rma rma) {
        if (rma == null) return;
        final Map<String, Object> p = new HashMap<>();
        p.put("rmaId", rma.getId() != null ? rma.getId().toString() : null);
        p.put("deliveryId", rma.getDeliveryId() != null ? rma.getDeliveryId().toString() : null);
        p.put("status", rma.getStatus() != null ? rma.getStatus().name() : null);
        p.put("clientName", rma.getClientName());
        executeAfterCommitAsync(() -> {
            log.info("EVENT return.status_changed rmaId={} status={}", p.get("rmaId"), p.get("status"));
            CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
                .source("/delivery-service")
                .type("return.status_changed")
                .data(p)
                .build();
            notificationGateway.broadcast("/topic/admin.deliveries", envelope);
        });
    }

    // ── Route events ──────────────────────────────────────────────────────────

    public void publishRouteValidated(Route route) {
        // Build the payload in the transactional thread — it touches lazy associations
        // (route.getStops()) that are unavailable once the async after-commit task runs.
        final RouteEventPayload p = routePayload("route.validated", route);
        final UUID driverId = route.getDriverId();
        final UUID routeId = route.getId();
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.validated routeId={} driverId={}", routeId, driverId);
            sendRoute("route.validated", p);

            if (driverId != null) {
                sendFcmFatPayload(driverId.toString(), "ROUTE_VALIDATED", p);
            }
        });
    }

    public void publishRouteCancelled(Route route, String reason) {
        final RouteEventPayload p = routePayload("route.cancelled", route);
        p.setReason(reason);
        final UUID driverId = route.getDriverId();
        final UUID routeId = route.getId();
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.cancelled routeId={} driverId={} reason={}", routeId, driverId, reason);
            sendRoute("route.cancelled", p);
            if (driverId != null) {
                sendFcmFatPayload(driverId.toString(), "ROUTE_CANCELLED", p);
            }
        });
    }

    public void publishRouteScheduleChanged(Route route) {
        final RouteEventPayload p = routePayload("route.schedule_changed", route);
        final UUID driverId = route.getDriverId();
        final UUID routeId = route.getId();
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.schedule_changed routeId={} driverId={}", routeId, driverId);
            sendRoute("route.schedule_changed", p);

            if (driverId != null) {
                sendFcmFatPayload(driverId.toString(), "ROUTE_SCHEDULE_CHANGED", p);
            }
        });
    }

    public void publishRouteStopAdded(Route route, String clientName) {
        final RouteEventPayload p = routePayload("route.stop_added", route);
        p.setClientName(clientName);
        final UUID driverId = route.getDriverId();
        final UUID routeId = route.getId();
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.stop_added routeId={} driverId={} client={}", routeId, driverId, clientName);
            sendRoute("route.stop_added", p);

            if (driverId != null) {
                sendFcmFatPayload(driverId.toString(), "ROUTE_STOP_ADDED", p);
            }
        });
    }

    public void publishRouteStopRemoved(Route route, String clientName) {
        publishRouteStopRemoved(route, clientName, null, null);
    }

    public void publishRouteStopRemoved(Route route, String clientName, String erpOrderId, String reason) {
        final RouteEventPayload p = routePayload("route.stop_removed", route);
        p.setClientName(clientName);
        p.setErpOrderId(erpOrderId);
        p.setReason(reason);
        final UUID driverId = route.getDriverId();
        final UUID routeId = route.getId();
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.stop_removed routeId={} driverId={} client={}", routeId, driverId, clientName);
            sendRoute("route.stop_removed", p);

            if (driverId != null) {
                sendFcmFatPayload(driverId.toString(), "ROUTE_STOP_REMOVED", p);
            }
        });
    }

    public void publishStopsTransferred(UUID sourceDriverId, UUID targetDriverId, int count, boolean requiresHandoff) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT stops.transferred sourceDriver={} targetDriver={} count={} handoff={}", sourceDriverId, targetDriverId, count, requiresHandoff);
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
            notificationGateway.broadcast("/topic/admin.routes", envelope);
        });
    }
}
