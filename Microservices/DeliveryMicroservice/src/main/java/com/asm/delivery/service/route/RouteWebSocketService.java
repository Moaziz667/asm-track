package com.asm.delivery.service.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RouteWebSocketService {

    private final SimpMessagingTemplate messaging;

    /**
     * Pushes a route lifecycle event to the driver's personal topic.
     */
    public void notifyDriver(UUID driverId, String event, UUID routeId, String routeName) {
        if (driverId == null) {
            log.debug("notifyDriver: skipped — no driverId for event={} routeId={}", event, routeId);
            return;
        }
        String destination = "/topic/driver." + driverId;
        Map<String, String> payload = Map.of(
                "event", event,
                "routeId", routeId.toString(),
                "routeName", routeName != null ? routeName : ""
        );
        try {
            messaging.convertAndSend(destination, payload);
            messaging.convertAndSend("/topic/admin/routes", payload);
            log.info("notifyDriver: sent event={} to driverId={} routeId={}", event, driverId, routeId);
        } catch (Exception e) {
            log.warn("notifyDriver: failed to send event={} to driverId={}: {}", event, driverId, e.getMessage());
        }
    }

    /**
     * Pushes a general route update signal to the admin dashboard.
     */
    public void notifyRouteUpdate(UUID routeId) {
        if (routeId == null) return;
        try {
            messaging.convertAndSend("/topic/admin/routes", Map.of(
                    "event", "ROUTE_UPDATED",
                    "routeId", routeId.toString()
            ));
            log.info("notifyRouteUpdate: sent update for routeId={} to admin", routeId);
        } catch (Exception e) {
            log.warn("notifyRouteUpdate: failed for routeId={}: {}", routeId, e.getMessage());
        }
    }
}
