package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.Route;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// TODO Phase 2: replace with n8n webhook calls
@Service
@Slf4j
public class EventPublisher {

    @Autowired(required = false)
    private FcmNotificationService fcm;

    public void publishDeliveryCreated(Order order, Delivery delivery) {
        log.info("EVENT delivery.created orderId={} deliveryId={}",
                order != null ? order.getId() : null, delivery.getId());
    }

    public void publishDeliveryScheduled(Order order, Delivery delivery, UUID driverId) {
        log.info("EVENT delivery.scheduled orderId={} deliveryId={} driverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), driverId);
        if (fcm != null && driverId != null) {
            String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Livraison";
            fcm.sendToDriver(driverId.toString(),
                    "Nouvelle livraison assignée",
                    ref + " est prête à être récupérée");
        }
    }

    public void publishDeliveryPickedUp(Order order, Delivery delivery) {
        log.info("EVENT delivery.picked_up orderId={} deliveryId={}",
                order != null ? order.getId() : null, delivery.getId());
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery,
            BigDecimal lat, BigDecimal lng) {
        publishDeliveryInTransit(order, delivery, lat, lng, null, null, null, null, null);
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery,
            BigDecimal lat, BigDecimal lng,
            BigDecimal routeDistanceKm,
            Integer routeDurationMinutes,
            Integer transitSlaMinutesComputed,
            LocalDateTime routeEtaAt,
            String routeProvider) {
        log.info("EVENT delivery.in_transit orderId={} deliveryId={} lat={} lng={}",
                order != null ? order.getId() : null,
                delivery.getId(), lat, lng);
        if (routeDistanceKm != null || routeDurationMinutes != null || transitSlaMinutesComputed != null || routeEtaAt != null) {
            log.info("EVENT delivery.in_transit.route orderId={} deliveryId={} distanceKm={} durationMin={} slaMin={} etaAt={} provider={}",
                    order != null ? order.getId() : null,
                    delivery.getId(),
                    routeDistanceKm,
                    routeDurationMinutes,
                    transitSlaMinutesComputed,
                    routeEtaAt,
                    routeProvider);
        }
    }

    public void publishDeliveryCompleted(Order order, Delivery delivery, UUID driverId) {
        log.info("EVENT delivery.completed orderId={} deliveryId={} driverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), driverId);
    }

    public void publishDeliveryFailed(Order order, Delivery delivery, String reason) {
        log.info("EVENT delivery.failed orderId={} deliveryId={} reason={}",
                order != null ? order.getId() : null,
                delivery.getId(), reason);
    }

    public void publishDeliveryCancelled(Order order, Delivery delivery, UUID driverId) {
        log.info("EVENT delivery.cancelled orderId={} deliveryId={} driverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), driverId);
    }

    public void publishDeliveryReassigned(Order order, Delivery delivery, UUID previousDriverId, UUID newDriverId) {
        log.info("EVENT delivery.reassigned orderId={} deliveryId={} previousDriverId={} newDriverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), previousDriverId, newDriverId);
        String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Livraison";
        if (fcm != null && newDriverId != null) {
            fcm.sendToDriver(newDriverId.toString(),
                    "Livraison réassignée",
                    ref + " vous a été attribuée");
        }
        if (fcm != null && previousDriverId != null) {
            fcm.sendToDriver(previousDriverId.toString(),
                    "Livraison retirée",
                    ref + " a été attribuée à un autre livreur");
        }
    }

    public void publishDeliveryReplanned(Order order, Delivery delivery, UUID previousDriverId) {
        log.info("EVENT delivery.replanned orderId={} deliveryId={} previousDriverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), previousDriverId);
    }

    // ── Route lifecycle ───────────────────────────────────────────────────────

    public void publishRouteValidated(Route route) {
        log.info("EVENT route.validated routeId={} driverId={}", route.getId(), route.getDriverId());
        if (fcm != null && route.getDriverId() != null) {
            fcm.sendToDriver(route.getDriverId().toString(),
                    "Tournée prête à démarrer",
                    "\"" + route.getName() + "\" est validée — consultez-la avant de partir");
        }
    }

    public void publishRouteScheduleChanged(Route route) {
        log.info("EVENT route.schedule_changed routeId={} driverId={}", route.getId(), route.getDriverId());
        if (fcm != null && route.getDriverId() != null) {
            fcm.sendToDriver(route.getDriverId().toString(),
                    "Horaire modifié",
                    "L'heure de départ de \"" + route.getName() + "\" a été mise à jour");
        }
    }

    public void publishRouteStopAdded(Route route, String clientName) {
        log.info("EVENT route.stop_added routeId={} driverId={} client={}", route.getId(), route.getDriverId(), clientName);
        if (fcm != null && route.getDriverId() != null) {
            String client = clientName != null ? clientName : "nouveau client";
            fcm.sendToDriver(route.getDriverId().toString(),
                    "Nouvel arrêt ajouté",
                    client + " ajouté à votre tournée en cours");
        }
    }

    public void publishRouteStopRemoved(Route route, String clientName) {
        log.info("EVENT route.stop_removed routeId={} driverId={} client={}", route.getId(), route.getDriverId(), clientName);
        if (fcm != null && route.getDriverId() != null) {
            String client = clientName != null ? clientName : "Un arrêt";
            fcm.sendToDriver(route.getDriverId().toString(),
                    "Arrêt supprimé",
                    client + " a été retiré de votre tournée");
        }
    }

    public void publishDeliveryReassignedAway(Order order, Delivery delivery, UUID previousDriverId) {
        log.info("EVENT delivery.reassigned_away deliveryId={} previousDriverId={}", delivery.getId(), previousDriverId);
        if (fcm != null && previousDriverId != null) {
            String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Une livraison";
            fcm.sendToDriver(previousDriverId.toString(),
                    "Livraison retirée",
                    ref + " a été attribuée à un autre livreur");
        }
    }

    public void publishHandoffRequired(Order order, Delivery delivery, UUID newDriverId) {
        log.info("EVENT delivery.handoff_required deliveryId={} newDriverId={}", delivery.getId(), newDriverId);
        String ref = order != null && order.getErpOrderId() != null ? order.getErpOrderId() : "Colis";
        if (fcm != null && newDriverId != null) {
            fcm.sendToDriver(newDriverId.toString(),
                    "Transfert de colis en attente",
                    ref + " — scannez le QR du livreur précédent pour recevoir");
        }
    }

    public void publishStopsTransferred(UUID sourceDriverId, UUID targetDriverId, int count, boolean requiresHandoff) {
        log.info("EVENT stops.transferred sourceDriver={} targetDriver={} count={} handoff={}", sourceDriverId, targetDriverId, count, requiresHandoff);
        if (fcm == null) return;
        String countLabel = count + " arrêt" + (count > 1 ? "s" : "");
        if (sourceDriverId != null) {
            fcm.sendToDriver(sourceDriverId.toString(),
                    "Arrêts transférés",
                    countLabel + " retiré" + (count > 1 ? "s" : "") + " de votre tournée");
        }
        if (targetDriverId != null) {
            if (requiresHandoff) {
                fcm.sendToDriver(targetDriverId.toString(),
                        "Transfert de colis en attente",
                        countLabel + " à récupérer — scannez le QR du livreur précédent");
            } else {
                fcm.sendToDriver(targetDriverId.toString(),
                        "Arrêts ajoutés",
                        countLabel + " ajouté" + (count > 1 ? "s" : "") + " à votre tournée");
            }
        }
    }
}
