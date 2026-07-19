package com.asm.delivery.service.route;

import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.transport.TransportPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteExecutionServiceTest {

    @Mock RouteRepository routeRepository;
    @Mock RouteStopRepository routeStopRepository;
    @Mock HandoffRepository handoffRepository; // close() checks for a pending custody handoff before closing
    @Mock DeliveryRepository deliveryRepository;
    @Mock DeliveryStatusHistoryRepository deliveryStatusHistoryRepository;
    @Mock AuditLogService auditLogService;
    @Mock RoutePlanningService routePlanningService;
    @Mock TransportPort transportPort;
    @Mock DelayCalculationService delayCalculationService;
    @Mock RouteAutoCloseService routeAutoCloseService;

    @InjectMocks RouteExecutionService service;

    private Route route;
    private RouteStop stop;
    private UUID routeId;
    private UUID stopId;
    private UUID driverId;

    @BeforeEach
    void setUp() {
        routeId  = UUID.randomUUID();
        stopId   = UUID.randomUUID();
        driverId = UUID.randomUUID();

        route = new Route();
        route.setId(routeId);
        route.setDriverId(driverId);
        route.setStatus(RouteStatus.IN_PROGRESS);

        stop = new RouteStop();
        stop.setId(stopId);
        stop.setRoute(route);
        stop.setStatus(RouteStopStatus.PENDING);
        stop.setStopOrder(1);
    }

    // ── arrive() guards ────────────────────────────────────────────────────────

    @Test
    void arrive_blocksWhenStopAlreadyCompleted() {
        stop.setStatus(RouteStopStatus.COMPLETED);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findByRouteIdAndId(routeId, stopId)).thenReturn(Optional.of(stop));

        assertThatThrownBy(() -> service.arrive(routeId, stopId, driverId, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void arrive_blocksWhenStopFailed() {
        stop.setStatus(RouteStopStatus.FAILED);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findByRouteIdAndId(routeId, stopId)).thenReturn(Optional.of(stop));

        assertThatThrownBy(() -> service.arrive(routeId, stopId, driverId, null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void arrive_idempotentWhenAlreadyArrived() {
        stop.setStatus(RouteStopStatus.ARRIVED);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findByRouteIdAndId(routeId, stopId)).thenReturn(Optional.of(stop));
        when(routePlanningService.get(routeId)).thenReturn(null);

        assertThatCode(() -> service.arrive(routeId, stopId, driverId, null)).doesNotThrowAnyException();
        verify(routeStopRepository, never()).save(stop); // no re-save on idempotent call
    }

    @Test
    void arrive_blocksWhenDriverNotOwner() {
        route.setDriverId(UUID.randomUUID()); // different driver
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));

        assertThatThrownBy(() -> service.arrive(routeId, stopId, driverId, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not assigned");
    }

    // ── route auto-close ───────────────────────────────────────────────────────

    @Test
    void syncStopFromDelivery_advancesStopAndDelegatesFinalization() {
        stop.setStatus(RouteStopStatus.IN_TRANSIT);
        stop.setRoute(route);
        stop.setDeliveryId(UUID.randomUUID());

        when(routeStopRepository.findByDeliveryIdWithRoute(stop.getDeliveryId())).thenReturn(Optional.of(stop));
        when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)).thenReturn(List.of(stop));
        when(delayCalculationService.calculateCumulativeDelayMinutes(any(), any())).thenReturn(0);
        when(delayCalculationService.calculateOnTimeCompletionRate(any(), any())).thenReturn(1.0);

        service.syncStopFromDelivery(stop.getDeliveryId(), DeliveryStatus.DELIVERED, null, "delivered");

        // The stop is advanced to its terminal state, and finalization (CLOSED vs CANCELLED) is
        // delegated to RouteAutoCloseService — the single source of truth for that decision.
        assertThat(stop.getStatus()).isEqualTo(RouteStopStatus.COMPLETED);
        verify(routeStopRepository).save(stop);
        verify(routeAutoCloseService).finalizeIfResolved(route);
    }

    @Test
    void syncStopFromDelivery_doesNotMoveStopBackward() {
        stop.setStatus(RouteStopStatus.COMPLETED);
        stop.setDeliveryId(UUID.randomUUID());

        when(routeStopRepository.findByDeliveryIdWithRoute(stop.getDeliveryId())).thenReturn(Optional.of(stop));

        // Attempting to sync IN_TRANSIT after COMPLETED should be a no-op
        service.syncStopFromDelivery(stop.getDeliveryId(), DeliveryStatus.IN_TRANSIT, null, "");

        assertThat(stop.getStatus()).isEqualTo(RouteStopStatus.COMPLETED); // unchanged
        verify(routeStopRepository, never()).save(stop);
        verifyNoInteractions(routeAutoCloseService);
    }

    // ── admin close() ──────────────────────────────────────────────────────────

    @Test
    void close_blocksNonInProgressRoute() {
        route.setStatus(RouteStatus.VALIDATED);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));

        assertThatThrownBy(() -> service.close(routeId))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("in-progress");
    }

    @Test
    void close_blocksWhenStopsStillActive() {
        stop.setStatus(RouteStopStatus.ARRIVED);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)).thenReturn(List.of(stop));

        assertThatThrownBy(() -> service.close(routeId))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("still active");
    }
}
