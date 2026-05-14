package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.Route;
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> deliveryPayload(String event, Order order, Delivery delivery) {
        Map<String, Object> m = new HashMap<>();
        m.put("event", event);
        m.put("deliveryId", delivery.getId());
        m.put("orderId", order != null ? (order.getErpOrderId() != null ? order.getErpOrderId() : order.getId()) : null);
        m.put("status", delivery.getStatus());
        m.put("companyId", delivery.getCompanyId());
        m.put("clientName", order != null ? order.getClientName() : null);
        return m;
    }

    private Map<String, Object> routePayload(String event, Route route) {
        Map<String, Object> m = new HashMap<>();
        m.put("event", event);
        m.put("routeId", route.getId());
        m.put("routeName", route.getName());
        m.put("status", route.getStatus());
        m.put("companyId", route.getCompanyId());
        return m;
    }

    private void sendDelivery(Map<String, Object> payload) {
        UUID companyId = (UUID) payload.get("companyId");
        if (companyId != null) {
            ws.convertAndSend("/topic/admin/" + companyId + "/deliveries", payload);
        } else {
            ws.convertAndSend("/topic/admin/deliveries", payload);
        }
    }

    private void sendRoute(Map<String, Object> payload) {
        UUID companyId = (UUID) payload.get("companyId");
        if (companyId != null) {
            ws.convertAndSend("/topic/admin/" + companyId + "/routes", payload);
        } else {
            ws.convertAndSend("/topic/admin/routes", payload);
        }
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

    public void publishSlaBreach(Delivery delivery, String motif, String severity, String message) {
        executeAfterCommitAsync(() -> {
            log.warn("EVENT sla.breach deliveryId={} motif={} severity={}", delivery.getId(), motif, severity);
            Map<String, Object> m = deliveryPayload("sla.breach", delivery.getOrder(), delivery);
            m.put("motif", motif);
            m.put("severity", severity);
            m.put("slaMessage", message);
            sendDelivery(m);
        });
    }

    // ── Delivery events ───────────────────────────────────────────────────────

    public void publishDeliveryCreated(Order order, Delivery delivery) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.created orderId={} deliveryId={}",
                    order != null ? order.getId() : null, delivery.getId());
            sendDelivery(deliveryPayload("delivery.created", order, delivery));
        });
    }

    public void publishDeliveryScheduled(Order order, Delivery delivery, UUID driverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.scheduled orderId={} deliveryId={} driverId={}",
                    order != null ? order.getId() : null, delivery.getId(), driverId);
            Map<String, Object> p = deliveryPayload("delivery.scheduled", order, delivery);
            p.put("driverId", driverId);
            sendDelivery(p);
            if (fcm != null && driverId != null) {
                String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Livraison";
                fcm.sendToDriver(driverId.toString(), "Nouvelle livraison assignée", ref + " est prête à être récupérée", "DELIVERY_ASSIGNED");
            }
        });
    }

    public void publishDeliveryPickedUp(Order order, Delivery delivery) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.picked_up orderId={} deliveryId={}",
                    order != null ? order.getId() : null, delivery.getId());
            sendDelivery(deliveryPayload("delivery.picked_up", order, delivery));
        });
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery,
            BigDecimal lat, BigDecimal lng) {
        executeAfterCommitAsync(() -> {
            publishDeliveryInTransit(order, delivery, lat, lng, null, null, null, null, null);
        });
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery,
            BigDecimal lat, BigDecimal lng,
            BigDecimal routeDistanceKm,
            Integer routeDurationMinutes,
            Integer transitSlaMinutesComputed,
            LocalDateTime routeEtaAt,
            String routeProvider) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.in_transit orderId={} deliveryId={} lat={} lng={}",
                    order != null ? order.getId() : null, delivery.getId(), lat, lng);
            Map<String, Object> p = deliveryPayload("delivery.in_transit", order, delivery);
            p.put("lat", lat);
            p.put("lng", lng);
            sendDelivery(p);
        });
    }

    public void publishDeliveryCompleted(Order order, Delivery delivery, UUID driverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.completed orderId={} deliveryId={} driverId={}",
                    order != null ? order.getId() : null, delivery.getId(), driverId);
            sendDelivery(deliveryPayload("delivery.completed", order, delivery));
        });
    }

    public void publishDeliveryFailed(Order order, Delivery delivery, String reason) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.failed orderId={} deliveryId={} reason={}",
                    order != null ? order.getId() : null, delivery.getId(), reason);
            Map<String, Object> p = deliveryPayload("delivery.failed", order, delivery);
            p.put("reason", reason);
            sendDelivery(p);
        });
    }

    public void publishDeliveryCancelled(Order order, Delivery delivery, UUID driverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.cancelled orderId={} deliveryId={} driverId={}",
                    order != null ? order.getId() : null, delivery.getId(), driverId);
            sendDelivery(deliveryPayload("delivery.cancelled", order, delivery));
        });
    }

    public void publishDeliveryReassigned(Order order, Delivery delivery, UUID previousDriverId, UUID newDriverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.reassigned orderId={} deliveryId={} previousDriverId={} newDriverId={}",
                    order != null ? order.getId() : null, delivery.getId(), previousDriverId, newDriverId);
            Map<String, Object> p = deliveryPayload("delivery.reassigned", order, delivery);
            p.put("previousDriverId", previousDriverId);
            p.put("newDriverId", newDriverId);
            sendDelivery(p);
            String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Livraison";
            if (fcm != null && newDriverId != null)
                fcm.sendToDriver(newDriverId.toString(), "Livraison réassignée", ref + " vous a été attribuée", "DELIVERY_ASSIGNED");
            if (fcm != null && previousDriverId != null)
                fcm.sendToDriver(previousDriverId.toString(), "Livraison retirée", ref + " a été attribuée à un autre livreur", "ROUTE_UPDATED");
        });
    }

    public void publishDeliveryReplanned(Order order, Delivery delivery, UUID previousDriverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.replanned orderId={} deliveryId={} previousDriverId={}",
                    order != null ? order.getId() : null, delivery.getId(), previousDriverId);
            sendDelivery(deliveryPayload("delivery.replanned", order, delivery));
        });
    }

    public void publishDeliveryReassignedAway(Order order, Delivery delivery, UUID previousDriverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.reassigned_away deliveryId={} previousDriverId={}", delivery.getId(), previousDriverId);
            sendDelivery(deliveryPayload("delivery.reassigned_away", order, delivery));
            if (fcm != null && previousDriverId != null) {
                String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Une livraison";
                fcm.sendToDriver(previousDriverId.toString(), "Livraison retirée", ref + " a été attribuée à un autre livreur");
            }
        });
    }

    public void publishHandoffRequired(Order order, Delivery delivery, UUID newDriverId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.handoff_required deliveryId={} newDriverId={}", delivery.getId(), newDriverId);
            sendDelivery(deliveryPayload("delivery.handoff_required", order, delivery));
            String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Colis";
            if (fcm != null && newDriverId != null)
                fcm.sendToDriver(newDriverId.toString(), "Transfert de colis en attente", ref + " — scannez le QR du livreur précédent pour recevoir", "HANDOFF_REQUIRED");
        });
    }

    public void publishErpOrdersReady(UUID companyId, int count) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT erp.orders_ready companyId={} count={}", companyId, count);
            Map<String, Object> m = new HashMap<>();
            m.put("event", "erp.orders_ready");
            m.put("count", count);
            m.put("companyId", companyId);
            ws.convertAndSend("/topic/admin/" + companyId + "/erp", m);
        });
    }

    public void publishErpSyncFailed(Order order) {
        executeAfterCommitAsync(() -> {
            log.error("EVENT erp.sync_failed orderId={} erpOrderId={}", order.getId(), order.getErpOrderId());
            Map<String, Object> m = new HashMap<>();
            m.put("event", "erp.sync_failed");
            m.put("orderId", order.getId());
            m.put("erpOrderId", order.getErpOrderId());
            m.put("clientName", order.getClientName());
            m.put("companyId", order.getCompanyId());
            m.put("retryCount", order.getSyncRetryCount());
            UUID companyId = order.getCompanyId();
            if (companyId != null) {
                ws.convertAndSend("/topic/admin/" + companyId + "/deliveries", m);
            } else {
                ws.convertAndSend("/topic/admin/deliveries", m);
            }
        });
    }

    // ── Route events ──────────────────────────────────────────────────────────

    public void publishRouteValidated(Route route) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.validated routeId={} driverId={}", route.getId(), route.getDriverId());
            sendRoute(routePayload("route.validated", route));
            if (fcm != null && route.getDriverId() != null)
                fcm.sendToDriver(route.getDriverId().toString(), "Tournée prête à démarrer",
                        "\"" + route.getName() + "\" est validée — consultez-la avant de partir", "ROUTE_VALIDATED");
        });
    }

    public void publishRouteScheduleChanged(Route route) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.schedule_changed routeId={} driverId={}", route.getId(), route.getDriverId());
            sendRoute(routePayload("route.schedule_changed", route));
            if (fcm != null && route.getDriverId() != null)
                fcm.sendToDriver(route.getDriverId().toString(), "Horaire modifié",
                        "L'heure de départ de \"" + route.getName() + "\" a été mise à jour");
        });
    }

    public void publishRouteStopAdded(Route route, String clientName) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT route.stop_added routeId={} driverId={} client={}", route.getId(), route.getDriverId(), clientName);
            sendRoute(routePayload("route.stop_added", route));
            if (fcm != null && route.getDriverId() != null) {
                String client = clientName != null ? clientName : "nouveau client";
                fcm.sendToDriver(route.getDriverId().toString(), "Nouvel arrêt ajouté", client + " ajouté à votre tournée en cours", "ROUTE_UPDATED");
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
            sendRoute(routePayload("route.stop_removed", route));
            if (fcm != null && route.getDriverId() != null) {
                String client = clientName != null ? clientName : "Un arrêt";
                String ref = erpOrderId != null ? " [" + erpOrderId + "]" : "";
                String note = reason != null && !reason.isBlank() ? " — " + reason : "";
                fcm.sendToDriver(route.getDriverId().toString(), "Arrêt supprimé" + ref,
                        client + " a été retiré de votre tournée" + note, "ROUTE_UPDATED");
            }
        });
    }

    public void publishStopsTransferred(UUID sourceDriverId, UUID targetDriverId, int count, boolean requiresHandoff) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT stops.transferred sourceDriver={} targetDriver={} count={} handoff={}", sourceDriverId, targetDriverId, count, requiresHandoff);
            if (fcm == null) return;
            String countLabel = count + " arrêt" + (count > 1 ? "s" : "");
            if (sourceDriverId != null)
                fcm.sendToDriver(sourceDriverId.toString(), "Arrêts transférés", countLabel + " retiré" + (count > 1 ? "s" : "") + " de votre tournée");
            if (targetDriverId != null) {
                if (requiresHandoff)
                    fcm.sendToDriver(targetDriverId.toString(), "Transfert de colis en attente", countLabel + " à récupérer — scannez le QR du livreur précédent");
                else
                    fcm.sendToDriver(targetDriverId.toString(), "Arrêts ajoutés", countLabel + " ajouté" + (count > 1 ? "s" : "") + " à votre tournée");
            }
        });
    }

    public void publishHandoffConfirmed(UUID deliveryId, UUID routeId, UUID driverId, UUID companyId) {
        executeAfterCommitAsync(() -> {
            log.info("EVENT delivery.handoff_confirmed deliveryId={} routeId={} driverId={}", deliveryId, routeId, driverId);
            Map<String, Object> m = new HashMap<>();
            m.put("event", "delivery.handoff_confirmed");
            m.put("deliveryId", deliveryId);
            m.put("routeId", routeId);
            m.put("driverId", driverId);
            m.put("companyId", companyId);
            ws.convertAndSend("/topic/admin/routes", m);
        });
    }
}
