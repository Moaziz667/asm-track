package com.asm.delivery.service.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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
        executeAfterCommitAsync(() -> {
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
                messaging.convertAndSend("/topic/admin.routes", payload);
                log.info("notifyDriver: sent event={} to driverId={} routeId={}", event, driverId, routeId);
            } catch (Exception e) {
                log.warn("notifyDriver: failed to send event={} to driverId={}: {}", event, driverId, e.getMessage());
            }
        });
    }

    public void notifyDriverStopAdded(UUID driverId, UUID routeId, String routeName, String clientName) {
        executeAfterCommitAsync(() -> {
            if (driverId == null) return;
            String destination = "/topic/driver." + driverId;
            Map<String, String> payload = new HashMap<>();
            payload.put("event", "STOP_ADDED");
            payload.put("routeId", routeId.toString());
            payload.put("routeName", routeName != null ? routeName : "");
            if (clientName != null) payload.put("clientName", clientName);
            try {
                messaging.convertAndSend(destination, payload);
                messaging.convertAndSend("/topic/admin.routes", payload);
                log.info("notifyDriverStopAdded: sent to driverId={} client={}", driverId, clientName);
            } catch (Exception e) {
                log.warn("notifyDriverStopAdded: failed for driverId={}: {}", driverId, e.getMessage());
            }
        });
    }

    public void notifyDriverStopRemoved(UUID driverId, UUID routeId, String routeName,
                                         String clientName, String erpOrderId, String reason) {
        executeAfterCommitAsync(() -> {
            if (driverId == null) return;
            String destination = "/topic/driver." + driverId;
            Map<String, String> payload = new HashMap<>();
            payload.put("event", "STOP_REMOVED");
            payload.put("routeId", routeId.toString());
            payload.put("routeName", routeName != null ? routeName : "");
            if (clientName != null) payload.put("clientName", clientName);
            if (erpOrderId != null) payload.put("erpOrderId", erpOrderId);
            if (reason != null && !reason.equals("CANCELLED")) payload.put("reason", reason);
            try {
                messaging.convertAndSend(destination, payload);
                messaging.convertAndSend("/topic/admin.routes", payload);
                log.info("notifyDriverStopRemoved: sent to driverId={} client={}", driverId, clientName);
            } catch (Exception e) {
                log.warn("notifyDriverStopRemoved: failed for driverId={}: {}", driverId, e.getMessage());
            }
        });
    }

    /**
     * Pushes a general route update signal to the admin dashboard.
     */
    public void notifyRouteUpdate(UUID routeId) {
        executeAfterCommitAsync(() -> {
            if (routeId == null) return;
            try {
                messaging.convertAndSend("/topic/admin.routes", Map.of(
                        "event", "ROUTE_UPDATED",
                        "routeId", routeId.toString()
                ));
                log.info("notifyRouteUpdate: sent update for routeId={} to admin", routeId);
            } catch (Exception e) {
                log.warn("notifyRouteUpdate: failed for routeId={}: {}", routeId, e.getMessage());
            }
        });
    }

    public void notifyDriverStatusChanged(UUID companyId, UUID driverId, String status, String driverName) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String destination = "/topic/admin." + companyId + ".drivers";
                Map<String, String> payload = new java.util.HashMap<>();
                payload.put("event", "driver.status_changed");
                payload.put("driverId", driverId.toString());
                payload.put("companyId", companyId.toString());
                payload.put("status", status);
                payload.put("driverName", driverName);
                messaging.convertAndSend(destination, payload);
                log.info("notifyDriverStatusChanged: driverId={} status={} -> {}", driverId, status, destination);
            } catch (Exception e) {
                log.warn("notifyDriverStatusChanged: failed for driverId={}: {}", driverId, e.getMessage());
            }
        });
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
}
