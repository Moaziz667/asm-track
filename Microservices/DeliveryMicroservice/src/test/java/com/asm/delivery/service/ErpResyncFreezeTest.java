package com.asm.delivery.service;

import com.asm.delivery.dto.canonical.CanonicalDelivery;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A re-sync refreshes an order from the ERP — correct an address in Odoo, re-import, ASM follows.
 * That must keep working. But once the delivery is scheduled, a route has been planned around that
 * address and the driver carries a printed stop; past PICKED_UP the parcel is physically in their
 * hands. Rewriting the destination there sends someone to the wrong place, and the old code also
 * nulled the geocode, so the planned route lost its coordinates too.
 *
 * The line sits on {@link DeliveryStatus}, not {@code OrderStatus}: an order whose parcel is
 * IN_TRANSIT is still PENDING, which is why the original {@code isNew} flag protected nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ErpResyncFreezeTest {

    @Mock private OrderRepository orderRepo;
    @Mock private DeliveryRepository deliveryRepo;
    @Mock private com.asm.delivery.repository.DeliveryStatusHistoryRepository historyRepo;
    @Mock private EventPublisher eventPublisher;
    @Mock private OutboxProcessor outboxProcessor;
    @Mock private com.asm.delivery.erp.ErpLookupService erpLookupService;
    @Mock private AuditLogService auditLogService;
    @Mock private OrderGeocodingService orderGeocodingService;
    @Mock private com.asm.delivery.repository.RouteStopRepository routeStopRepository;
    @Mock private com.asm.delivery.repository.RouteRepository routeRepository;
    @Mock private com.asm.delivery.service.route.RoutePlanningService routePlanningService;
    @Mock private com.asm.delivery.service.route.RouteWebSocketService routeWebSocketService;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks private OrderService orderService;

    private static final String ERP_ID = "4212";
    private static final String OLD_ADDRESS = "12 rue de Carthage, Tunis";
    private static final String NEW_ADDRESS = "88 avenue Habib Bourguiba, Sousse";
    private static final java.math.BigDecimal LAT = new java.math.BigDecimal("36.8");
    private static final java.math.BigDecimal LNG = new java.math.BigDecimal("10.18");

    private Order existingOrder() {
        Order order = Order.builder().build();
        order.setId(UUID.randomUUID());
        order.setErpOrderId(ERP_ID);
        order.setDropoffAddress(OLD_ADDRESS);
        order.setDropoffLat(LAT);
        order.setDropoffLng(LNG);
        return order;
    }

    /** An ERP payload that moves the delivery elsewhere and renames the customer. */
    private CanonicalDelivery movedTo(String address) {
        CanonicalDelivery.Address addr = new CanonicalDelivery.Address();
        addr.setFullAddress(address);
        addr.setCity("Sousse");

        CanonicalDelivery.Contact contact = new CanonicalDelivery.Contact();
        contact.setName("Client renommé");

        CanonicalDelivery.Destination dest = new CanonicalDelivery.Destination();
        dest.setAddress(addr);
        dest.setContact(contact);

        CanonicalDelivery.Metadata meta = new CanonicalDelivery.Metadata();
        meta.setExternalId(ERP_ID);

        CanonicalDelivery canonical = new CanonicalDelivery();
        canonical.setDestination(dest);
        canonical.setMetadata(meta);
        return canonical;
    }

    private void givenDeliveryIs(DeliveryStatus status) {
        Delivery delivery = Delivery.builder().build();
        delivery.setStatus(status);
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(any()))
                .thenReturn(Optional.of(delivery));
    }

    @Test
    void appliesTheErpAddressWhileTheDeliveryIsStillUnscheduled() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        givenDeliveryIs(DeliveryStatus.UNSCHEDULED);

        orderService.createFromCanonical(movedTo(NEW_ADDRESS));

        assertThat(order.getDropoffAddress()).isEqualTo(NEW_ADDRESS);
    }

    @Test
    void appliesTheErpAddressWhenNoDeliveryExistsYet() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());

        orderService.createFromCanonical(movedTo(NEW_ADDRESS));

        assertThat(order.getDropoffAddress()).isEqualTo(NEW_ADDRESS);
    }

    @Test
    void refusesToMoveADeliveryThatIsAlreadyScheduled() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        givenDeliveryIs(DeliveryStatus.SCHEDULED);

        orderService.createFromCanonical(movedTo(NEW_ADDRESS));

        assertThat(order.getDropoffAddress()).isEqualTo(OLD_ADDRESS);
    }

    @Test
    void refusesToMoveAParcelThatIsAlreadyInTransit() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        givenDeliveryIs(DeliveryStatus.IN_TRANSIT);

        orderService.createFromCanonical(movedTo(NEW_ADDRESS));

        assertThat(order.getDropoffAddress()).isEqualTo(OLD_ADDRESS);
    }

    /**
     * The regression that made this worse than a stale address: the planned route kept its stop but
     * lost the coordinates it was built from.
     */
    @Test
    void keepsTheGeocodeOfAScheduledDelivery() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        givenDeliveryIs(DeliveryStatus.SCHEDULED);

        orderService.createFromCanonical(movedTo(NEW_ADDRESS));

        assertThat(order.getDropoffLat()).isEqualTo(LAT);
        assertThat(order.getDropoffLng()).isEqualTo(LNG);
    }

    /** Freezing where and when must not freeze everything: the driver still wants a correct name. */
    @Test
    void keepsSyncingNonLogisticsFieldsOnAScheduledDelivery() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        givenDeliveryIs(DeliveryStatus.SCHEDULED);

        orderService.createFromCanonical(movedTo(NEW_ADDRESS));

        assertThat(order.getClientName()).isEqualTo("Client renommé");
    }

    /** A divergence nobody is told about is the same failure, one layer down. */
    @Test
    void recordsTheRefusalSoADispatcherCanAct() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        givenDeliveryIs(DeliveryStatus.SCHEDULED);

        orderService.createFromCanonical(movedTo(NEW_ADDRESS));

        org.mockito.Mockito.verify(auditLogService).logAction(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ERP_SYNC_REFUSED_ON_COMMITTED_ORDER"),
                org.mockito.ArgumentMatchers.eq("DELIVERY"),
                org.mockito.ArgumentMatchers.eq(order.getId().toString()),
                org.mockito.ArgumentMatchers.any());
    }

    /** An unchanged re-sync is not news — alerting on it would train dispatchers to ignore alerts. */
    @Test
    void staysSilentWhenTheErpAgreesWithTheFrozenOrder() {
        Order order = existingOrder();
        when(orderRepo.findByErpOrderId(ERP_ID)).thenReturn(Optional.of(order));
        givenDeliveryIs(DeliveryStatus.SCHEDULED);

        orderService.createFromCanonical(movedTo(OLD_ADDRESS));

        org.mockito.Mockito.verify(auditLogService, org.mockito.Mockito.never()).logAction(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("ERP_SYNC_REFUSED_ON_COMMITTED_ORDER"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
