package com.asm.delivery.erp;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OutboxEvent;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.repository.OutboxRepository;
import com.asm.delivery.service.OutboxProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErpResyncServiceTest {

    @Mock OrderRepository orderRepo;
    @Mock DeliveryRepository deliveryRepo;
    @Mock OutboxRepository outboxRepo;
    @Mock OutboxProcessor outboxProcessor;
    // Real mapper so payload-matching actually parses JSON.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ErpResyncService service;

    private UUID orderId;
    private Order order;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        service = new ErpResyncService(orderRepo, deliveryRepo, outboxRepo, outboxProcessor, objectMapper);
        orderId = UUID.randomUUID();
        order = Order.builder()
                .id(orderId)
                .odooSyncStatus("SYNC_FAILED")
                .blNumber("S00110")
                .erpOrderId("S00110")
                .lastSyncOp("STOCK_FULL")
                .syncRetryCount(5)
                .build();
        delivery = Delivery.builder().id(UUID.randomUUID()).order(order).build();
    }

    @Test
    void prefersRequeuingTheOriginalFailedOutboxEvent() throws Exception {
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));
        OutboxEvent failed = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .eventType("ERP_SYNC_STOCK")
                .status("FAILED")
                .payload(objectMapper.writeValueAsString(java.util.Map.of("orderId", orderId.toString(), "op", "STOCK_FULL")))
                .build();
        when(outboxRepo.findByStatusOrderByCreatedAtAsc("FAILED")).thenReturn(List.of(failed));

        var result = service.resync(orderId);

        assertThat(result.queued()).isTrue();
        assertThat(result.status()).isEqualTo("PENDING_SYNC");
        assertThat(failed.getStatus()).isEqualTo("PENDING");
        assertThat(failed.getRetryCount()).isZero();
        assertThat(order.getOdooSyncStatus()).isEqualTo("PENDING_SYNC");
        assertThat(order.getSyncRetryCount()).isZero();
        verify(outboxRepo).save(failed);
        // No fresh enqueue when we re-drive the existing event.
        verify(outboxProcessor, never()).enqueue(anyString(), any());
    }

    @Test
    void fallsBackToFreshOutboxEnqueueWhenNoFailedEventExists() {
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));
        when(outboxRepo.findByStatusOrderByCreatedAtAsc("FAILED")).thenReturn(List.of());

        var result = service.resync(orderId);

        assertThat(result.queued()).isTrue();
        assertThat(order.getOdooSyncStatus()).isEqualTo("PENDING_SYNC");
        // Routes through the transactional outbox, not a direct publish.
        verify(outboxProcessor).enqueue(eq("ERP_SYNC_STOCK"), any());
    }

    @Test
    void refusesToResyncWhenOrderIsNotFailed() {
        order.setOdooSyncStatus("SYNCED");
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        var result = service.resync(orderId);

        assertThat(result.queued()).isFalse();
        verifyNoInteractions(outboxProcessor);
        verify(outboxRepo, never()).save(any());
    }

    @Test
    void refusesUnreconstructableOpWithoutAFailedEvent() {
        order.setLastSyncOp("POD"); // POD/RETURN/PARTIAL can't be rebuilt from delivery state
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));
        when(outboxRepo.findByStatusOrderByCreatedAtAsc("FAILED")).thenReturn(List.of());

        var result = service.resync(orderId);

        assertThat(result.queued()).isFalse();
        assertThat(result.reason()).contains("manual review");
        verifyNoInteractions(outboxProcessor);
    }

    @Test
    void refusesWhenOrderHasNoErpReference() {
        order.setErpOrderId(null);
        order.setErpExternalRef(null);
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));

        var result = service.resync(orderId);

        assertThat(result.queued()).isFalse();
        assertThat(result.reason()).contains("ERP reference");
    }
}
