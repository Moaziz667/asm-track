package com.asm.delivery.service.route;

import com.asm.delivery.event.CloudEventWrapper;
import com.asm.delivery.event.RouteEventPayload;
import com.asm.tenant.TenantContext;
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

    /** Tenant-decorated pool from AsyncConfig — NEVER publish on the ForkJoin common pool. */
    @org.springframework.beans.factory.annotation.Qualifier("eventExecutor")
    private final java.util.concurrent.Executor eventExecutor;

    private UUID getCompanyId() {
        return TenantContext.get();
    }

    /**
     * Tenant-scoped topic in relay-safe DOT notation; no silent global fallback — see
     * EventPublisher.tenantTopic for the full rationale (slash destinations are rejected by the
     * RabbitMQ STOMP relay, killing every tenant-scoped event).
     */
    private String tenantTopic(String subtopic) {
        UUID companyId = getCompanyId();
        if (companyId != null) {
            return "/topic/company." + companyId + "." + subtopic;
        }
        log.error("Tenant event published WITHOUT TenantContext (subtopic={}) — event routed to dead "
                + "destination; fix the calling path's tenant propagation", subtopic);
        return "/topic/untenanted." + subtopic;
    }

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
            
            RouteEventPayload payload = RouteEventPayload.builder()
                .routeId(routeId.toString())
                .routeName(routeName != null ? routeName : "")
                .driverId(driverId.toString())
                .build();
                
            CloudEventWrapper<RouteEventPayload> envelope = CloudEventWrapper.<RouteEventPayload>builder()
                .source("/route-service")
                .type(event)
                .data(payload)
                .build();

            try {
                messaging.convertAndSend(destination, envelope);
                messaging.convertAndSend(tenantTopic("admin.routes"), envelope);
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
            
            RouteEventPayload payload = RouteEventPayload.builder()
                .routeId(routeId.toString())
                .routeName(routeName != null ? routeName : "")
                .driverId(driverId.toString())
                .clientName(clientName)
                .build();
                
            CloudEventWrapper<RouteEventPayload> envelope = CloudEventWrapper.<RouteEventPayload>builder()
                .source("/route-service")
                .type("STOP_ADDED")
                .data(payload)
                .build();

            try {
                messaging.convertAndSend(destination, envelope);
                messaging.convertAndSend(tenantTopic("admin.routes"), envelope);
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
            
            RouteEventPayload payload = RouteEventPayload.builder()
                .routeId(routeId.toString())
                .routeName(routeName != null ? routeName : "")
                .driverId(driverId.toString())
                .clientName(clientName)
                .erpOrderId(erpOrderId)
                .reason(reason != null && !reason.equals("CANCELLED") ? reason : null)
                .build();
                
            CloudEventWrapper<RouteEventPayload> envelope = CloudEventWrapper.<RouteEventPayload>builder()
                .source("/route-service")
                .type("STOP_REMOVED")
                .data(payload)
                .build();

            try {
                messaging.convertAndSend(destination, envelope);
                messaging.convertAndSend(tenantTopic("admin.routes"), envelope);
                log.info("notifyDriverStopRemoved: sent to driverId={} client={}", driverId, clientName);
            } catch (Exception e) {
                log.warn("notifyDriverStopRemoved: failed for driverId={}: {}", driverId, e.getMessage());
            }
        });
    }

    /**
     * Admin dashboard: a driver has started a route (left the depot). Carries precise info
     * so the dashboard can show "{driver} a démarré {route} — {n} arrêts".
     */
    public void notifyRouteStarted(UUID routeId, String routeName, UUID driverId,
                                   String driverName, int stopCount,
                                   java.time.LocalDateTime startedAt) {
        executeAfterCommitAsync(() -> {
            RouteEventPayload payload = RouteEventPayload.builder()
                .routeId(routeId != null ? routeId.toString() : null)
                .routeName(routeName != null ? routeName : "")
                .driverId(driverId != null ? driverId.toString() : null)
                .driverName(driverName)
                .status("IN_PROGRESS")
                .stopCount(stopCount)
                .occurredAt(startedAt)
                .build();

            CloudEventWrapper<RouteEventPayload> envelope = CloudEventWrapper.<RouteEventPayload>builder()
                .source("/route-service")
                .type("ROUTE_STARTED")
                .data(payload)
                .build();

            try {
                messaging.convertAndSend(tenantTopic("admin.routes"), envelope);
                log.info("notifyRouteStarted: routeId={} driverId={} stops={}", routeId, driverId, stopCount);
            } catch (Exception e) {
                log.warn("notifyRouteStarted: failed for routeId={}: {}", routeId, e.getMessage());
            }
        });
    }

    /**
     * Admin dashboard: a driver has confirmed loading at a depot (pickup stop completed).
     * Carries depot name + parcel count so the dashboard can show
     * "{driver} a chargé {n} colis au dépôt {depot}".
     */
    public void notifyPickupConfirmed(UUID routeId, String routeName, UUID driverId,
                                      String driverName, String depotName, int parcelCount,
                                      java.time.LocalDateTime confirmedAt) {
        executeAfterCommitAsync(() -> {
            RouteEventPayload payload = RouteEventPayload.builder()
                .routeId(routeId != null ? routeId.toString() : null)
                .routeName(routeName != null ? routeName : "")
                .driverId(driverId != null ? driverId.toString() : null)
                .driverName(driverName)
                .depotName(depotName)
                .parcelCount(parcelCount)
                .occurredAt(confirmedAt)
                .build();

            CloudEventWrapper<RouteEventPayload> envelope = CloudEventWrapper.<RouteEventPayload>builder()
                .source("/route-service")
                .type("PICKUP_CONFIRMED")
                .data(payload)
                .build();

            try {
                messaging.convertAndSend(tenantTopic("admin.routes"), envelope);
                log.info("notifyPickupConfirmed: routeId={} depot={} parcels={}", routeId, depotName, parcelCount);
            } catch (Exception e) {
                log.warn("notifyPickupConfirmed: failed for routeId={}: {}", routeId, e.getMessage());
            }
        });
    }

    /**
     * Pushes a general route update signal to the admin dashboard.
     */
    public void notifyRouteUpdate(UUID routeId) {
        executeAfterCommitAsync(() -> {
            if (routeId == null) return;
            
            RouteEventPayload payload = RouteEventPayload.builder()
                .routeId(routeId.toString())
                .build();
                
            CloudEventWrapper<RouteEventPayload> envelope = CloudEventWrapper.<RouteEventPayload>builder()
                .source("/route-service")
                .type("ROUTE_UPDATED")
                .data(payload)
                .build();

            try {
                messaging.convertAndSend(tenantTopic("admin.routes"), envelope);
                log.info("notifyRouteUpdate: sent update for routeId={} to admin", routeId);
            } catch (Exception e) {
                log.warn("notifyRouteUpdate: failed for routeId={}: {}", routeId, e.getMessage());
            }
        });
    }

    public void notifyDriverStatusChanged(UUID driverId, String status, String driverName) {
        java.util.concurrent.CompletableFuture.runAsync(withTenant(() -> {
            try {
                String destination = tenantTopic("admin.drivers");
                
                Map<String, String> payload = new HashMap<>();
                payload.put("driverId", driverId.toString());
                payload.put("status", status);
                payload.put("driverName", driverName);
                
                CloudEventWrapper<Map<String, String>> envelope = CloudEventWrapper.<Map<String, String>>builder()
                    .source("/route-service")
                    .type("driver.status_changed")
                    .data(payload)
                    .build();

                messaging.convertAndSend(destination, envelope);
                log.info("notifyDriverStatusChanged: driverId={} status={} destination={}", driverId, status, destination);
            } catch (Exception e) {
                log.warn("notifyDriverStatusChanged: failed for driverId={}: {}", driverId, e.getMessage());
            }
        }), eventExecutor);
    }

    private void executeAfterCommitAsync(Runnable runnable) {
        // The async worker has no TenantContext, so tenantTopic() inside the task would route to the
        // dead untenanted destination. Capture the tenant here and restore it (the eventExecutor's
        // decorator also does this — kept as belt-and-braces since the capture points differ).
        final Runnable tenantAware = withTenant(runnable);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        java.util.concurrent.CompletableFuture.runAsync(tenantAware, eventExecutor);
                    }
                }
            );
        } else {
            java.util.concurrent.CompletableFuture.runAsync(tenantAware, eventExecutor);
        }
    }

    /** Re-establish the submitting thread's tenant inside an async task, then clear it afterwards. */
    private Runnable withTenant(Runnable task) {
        final UUID tenant = TenantContext.get();
        return () -> {
            if (tenant != null) TenantContext.set(tenant);
            try {
                task.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
