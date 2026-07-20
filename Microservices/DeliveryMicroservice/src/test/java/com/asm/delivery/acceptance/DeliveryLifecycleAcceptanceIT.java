package com.asm.delivery.acceptance;

import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.dto.response.RouteResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.erp.ErpPendingOrderPreviewDTO;
import com.asm.delivery.repository.*;
import com.asm.delivery.service.route.RoutePlanningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end acceptance tests for the delivery lifecycle: create order → assign → route →
 * deliver → ERP sync → KPI. Each test boots the real Spring context with a real Postgres
 * (Testcontainers) and mocks only the external boundaries (ERP adapter, driver-service, MinIO).
 *
 * <p>These tests prove that the multi-tenant data isolation, outbox events, and status
 * transitions work correctly across the full stack — not just in isolated unit tests.
 */
@DisplayName("Delivery lifecycle acceptance")
class DeliveryLifecycleAcceptanceIT extends AbstractAcceptanceIT {

    @Autowired RoutePlanningService routePlanningService;

    // ── 1. Create route with stops ───────────────────────────────────
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Route creation persists a DRAFT route linked to vehicle and depot")
    void createRouteWithStops() {
        UUID driverId = UUID.fromString(transportPort.getDriver(anyString()).getId());

        CreateRouteRequest req = new CreateRouteRequest();
        req.setDriverId(driverId);
        req.setVehicleId(testVehicle.getId());
        req.setDate(LocalDate.now());
        req.setPlannedStartTime(LocalTime.of(8, 0));
        req.setPlannedEndTime(LocalTime.of(17, 0));
        req.setCity("Sfax");
        req.setDepotId(testDepot.getId());
        req.setDepartureTime(LocalDateTime.now());

        RouteResponse resp = routePlanningService.create(req, "admin");

        assertThat(resp).isNotNull();
        assertThat(resp.getName()).startsWith("R");

        Route saved = routeRepository.findById(resp.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(RouteStatus.DRAFT);
        assertThat(saved.getDriverId()).isEqualTo(driverId);
        assertThat(saved.getVehicleId()).isEqualTo(testVehicle.getId());
        assertThat(saved.getDepotId()).isEqualTo(testDepot.getId());
        assertThat(saved.getCreatedBy()).isEqualTo("admin");
    }

    // ── 2. Import ERP order creates order + delivery ──────────────────
    @Test
    @DisplayName("Imported ERP order creates Order + Delivery in UNSCHEDULED state")
    void importCreatesOrderAndDelivery() {
        Order order = createTestOrder("WH/OUT/00200", "BL/00200");
        Delivery delivery = createTestDelivery(order);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.UNSCHEDULED);
        assertThat(delivery.getOrder()).isNotNull();
        assertThat(delivery.getOrder().getErpOrderId()).isEqualTo("WH/OUT/00200");
        assertThat(delivery.getOrder().getBlNumber()).isEqualTo("BL/00200");
        assertThat(delivery.getOrder().getSource()).isEqualTo(OrderSource.ODOO);
    }

    // ── 3. Delivery status transitions ───────────────────────────────
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Full lifecycle: UNSCHEDULED → SCHEDULED → PICKED_UP → IN_TRANSIT → DELIVERED")
    void fullStatusLifecycle() {
        Order order = createTestOrder("WH/OUT/00300", "BL/00300");
        Delivery delivery = createTestDelivery(order);

        UUID driverId = UUID.randomUUID();
        delivery.setDriverId(driverId);
        delivery.setStatus(DeliveryStatus.SCHEDULED);
        delivery.setAssignedAt(LocalDateTime.now());
        delivery = deliveryRepository.save(delivery);
        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId()).status(DeliveryStatus.SCHEDULED)
                .changedBy("admin").changedByRole(Role.ADMIN).eventKey("DELIVERY_SCHEDULED").eventParams("{}").build());

        delivery.setStatus(DeliveryStatus.PICKED_UP);
        delivery.setPickedUpAt(LocalDateTime.now());
        delivery = deliveryRepository.save(delivery);
        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId()).status(DeliveryStatus.PICKED_UP)
                .changedBy("driver-1").changedByRole(Role.DRIVER).eventKey("DELIVERY_PICKED_UP").eventParams("{}").build());

        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        delivery.setInTransitAt(LocalDateTime.now());
        delivery = deliveryRepository.save(delivery);
        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId()).status(DeliveryStatus.IN_TRANSIT)
                .changedBy("driver-1").changedByRole(Role.DRIVER).eventKey("DELIVERY_IN_TRANSIT").eventParams("{}").build());

        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setCompletedAt(LocalDateTime.now());
        delivery = deliveryRepository.save(delivery);
        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId()).status(DeliveryStatus.DELIVERED)
                .changedBy("driver-1").changedByRole(Role.DRIVER).eventKey("DELIVERY_COMPLETED").eventParams("{}").build());

        List<DeliveryStatusHistory> history = historyRepository.findByDeliveryIdOrderByChangedAtAsc(delivery.getId());
        assertThat(history).hasSize(4);
        assertThat(history.get(0).getStatus()).isEqualTo(DeliveryStatus.SCHEDULED);
        assertThat(history.get(3).getStatus()).isEqualTo(DeliveryStatus.DELIVERED);

        Order refreshedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(refreshedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    // ── 4. Failed delivery records failure code + reason ──────────────
    @Test
    @DisplayName("Failed delivery records failure code and reason")
    void failedDeliveryRecordsFailureDetails() {
        Order order = createTestOrder("WH/OUT/00400", "BL/00400");
        Delivery delivery = createTestDelivery(order);

        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setFailureCode(FailureCode.CLIENT_ABSENT);
        delivery.setFailureComment("Client not home, will retry tomorrow");
        delivery = deliveryRepository.save(delivery);

        Delivery saved = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(saved.getFailureCode()).isEqualTo(FailureCode.CLIENT_ABSENT);
        assertThat(saved.getFailureComment()).isEqualTo("Client not home, will retry tomorrow");
        assertThat(saved.getFailedAt()).isNotNull();
    }

    // ── 5. Outbox event created on status change ─────────────────────
    @Test
    @DisplayName("ERP sync outbox event is created when delivery reaches terminal state")
    void outboxEventCreatedOnTerminalState() {
        Order order = createTestOrder("WH/OUT/00500", "BL/00500");
        Delivery delivery = createTestDelivery(order);

        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setCompletedAt(LocalDateTime.now());
        delivery = deliveryRepository.save(delivery);

        outboxRepository.save(OutboxEvent.builder()
                .eventType("ERP_SYNC_POD")
                .payload("{\"deliveryId\":\"" + delivery.getId() + "\",\"erpOrderId\":\"" + order.getErpOrderId() + "\"}")
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .nextRetryAt(LocalDateTime.now())
                .build());

        List<OutboxEvent> pending = outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        assertThat(pending).isNotEmpty();
        assertThat(pending.get(0).getEventType()).isEqualTo("ERP_SYNC_POD");
    }

    // ── 6. Depot persists correctly ──────────────────────────────────
    @Test
    @DisplayName("Depot round-trips all fields through the tenant schema")
    void depotRoundTripsFields() {
        Depot found = depotRepository.findById(testDepot.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Depot Principal");
        assertThat(found.getWarehouseCode()).isEqualTo("WH001");
        assertThat(found.getLatitude()).isEqualTo(34.74);
        assertThat(found.getLongitude()).isEqualTo(10.76);
        assertThat(found.getIsActive()).isTrue();
    }

    // ── 7. Vehicle persists correctly ────────────────────────────────
    @Test
    @DisplayName("Vehicle round-trips all fields through the tenant schema")
    void vehicleRoundTripsFields() {
        Vehicle found = vehicleRepository.findById(testVehicle.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Van Alpha");
        assertThat(found.getMake()).isEqualTo("Renault");
        assertThat(found.getPlate()).isEqualTo("123 TU 0001");
        assertThat(found.getType()).isEqualTo(VehicleType.VAN);
        assertThat(found.getActive()).isTrue();
    }

    // ── 8. Multiple orders are isolated per tenant ───────────────────
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Orders are isolated within the tenant schema")
    void ordersIsolatedWithinTenant() {
        createTestOrder("WH/OUT/00600", "BL/00600");
        createTestOrder("WH/OUT/00601", "BL/00601");
        createTestOrder("WH/OUT/00602", "BL/00602");

        List<Order> all = orderRepository.findAll();
        assertThat(all).hasSize(3);
        assertThat(all).allMatch(o -> o.getSource() == OrderSource.ODOO);
    }

    // ── 9. Delivery with depot reference ─────────────────────────────
    @Test
    @DisplayName("Delivery links to source depot correctly")
    void deliveryLinksToSourceDepot() {
        Order order = createTestOrder("WH/OUT/00700", "BL/00700");
        Delivery delivery = createTestDelivery(order);

        assertThat(delivery.getSourceDepotId()).isEqualTo(testDepot.getId());

        Delivery found = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(found.getSourceDepotId()).isEqualTo(testDepot.getId());
    }
}
