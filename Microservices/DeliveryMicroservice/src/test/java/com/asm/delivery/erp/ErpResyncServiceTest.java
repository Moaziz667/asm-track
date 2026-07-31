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
    @Mock com.asm.delivery.service.AuditLogService auditLogService;
    // A POD resync rebuilds its payload from the stored proof instead of refusing outright.
    @Mock com.asm.delivery.repository.ProofOfDeliveryRepository podRepo;
    // Real mapper so payload-matching actually parses JSON.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ErpResyncService service;

    private UUID orderId;
    private Order order;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        service = new ErpResyncService(orderRepo, deliveryRepo, outboxRepo, outboxProcessor, objectMapper,
                auditLogService, podRepo);
        orderId = UUID.randomUUID();
        order = Order.builder()
                .id(orderId)
                .erpSyncStatus("SYNC_FAILED")
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
        assertThat(order.getErpSyncStatus()).isEqualTo("PENDING_SYNC");
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
        assertThat(order.getErpSyncStatus()).isEqualTo("PENDING_SYNC");
        // Routes through the transactional outbox, not a direct publish.
        verify(outboxProcessor).enqueue(eq("ERP_SYNC_STOCK"), any());
    }

    @Test
    void refusesToResyncWhenOrderIsNotFailed() {
        order.setErpSyncStatus("SYNCED");
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        var result = service.resync(orderId);

        assertThat(result.queued()).isFalse();
        verifyNoInteractions(outboxProcessor);
        verify(outboxRepo, never()).save(any());
    }

    @Test
    void refusesUnreconstructableOpWithoutAFailedEvent() {
        // A reschedule carries a promised date that lives on the plan, not on the shipment, so
        // rebuilding it would mean inventing a date and writing it into the customer's ERP.
        order.setLastSyncOp("RESCHEDULE");
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));
        when(outboxRepo.findByStatusOrderByCreatedAtAsc("FAILED")).thenReturn(List.of());

        var result = service.resync(orderId);

        assertThat(result.queued()).isFalse();
        assertThat(result.reason()).contains("manual review");
        verifyNoInteractions(outboxProcessor);
    }

    @Test
    void rebuildsAProofOfDeliveryFromWhatTheDriverCaptured() {
        // Everything the ERP needs was stored before the ERP was ever called, so a failed POD can
        // be rebuilt faithfully — more faithfully than the live path, in fact, which stamped the
        // moment of sending rather than the moment of handover.
        order.setLastSyncOp("POD");
        var collectedAt = java.time.LocalDateTime.of(2026, 7, 30, 14, 5);
        var pod = com.asm.delivery.entity.ProofOfDelivery.builder()
                .collectedAt(collectedAt)
                .recipientName("Ali Ben Salah")
                .build();
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));
        when(outboxRepo.findByStatusOrderByCreatedAtAsc("FAILED")).thenReturn(List.of());
        when(podRepo.findByDeliveryId(delivery.getId())).thenReturn(Optional.of(pod));

        var result = service.resync(orderId);

        assertThat(result.queued()).isTrue();
        @SuppressWarnings("unchecked")
        var payload = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(outboxProcessor).enqueue(eq("ERP_SYNC_POD"), payload.capture());
        // The handover time, not the moment of re-sending: that is the point of rebuilding from
        // the stored proof rather than stamping now().
        assertThat(payload.getValue()).containsEntry("deliveredAt", collectedAt.toString());
        assertThat(payload.getValue()).containsEntry("recipientName", "Ali Ben Salah");
    }

    @Test
    void refusesAProofOfDeliveryItCannotRebuild() {
        // No stored proof means there is nothing truthful to send. Sending a made-up timestamp
        // into a customer's ERP would be worse than leaving the failure visible.
        order.setLastSyncOp("POD");
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));
        when(outboxRepo.findByStatusOrderByCreatedAtAsc("FAILED")).thenReturn(List.of());
        when(podRepo.findByDeliveryId(delivery.getId())).thenReturn(Optional.empty());

        var result = service.resync(orderId);

        assertThat(result.queued()).isFalse();
        assertThat(result.reason()).contains("No proof of delivery");
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

    // ── B1: reconciliation sweep for orders stuck in PENDING_SYNC ────────────────

    @Test
    void reEnqueuesAnOrderStuckInPendingSync() {
        order.setErpSyncStatus("PENDING_SYNC");
        order.setLastSyncOp("STOCK_FULL");
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));

        boolean requeued = service.reEnqueueStuckOrder(orderId);

        assertThat(requeued).isTrue();
        // The lost result is recovered by re-driving the sync through the outbox (idempotent downstream).
        verify(outboxProcessor).enqueue(eq("ERP_SYNC_STOCK"), any());
    }

    @Test
    void doesNotReEnqueueAnOrderThatIsNoLongerPending() {
        order.setErpSyncStatus("SYNCED"); // the result arrived after all
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        boolean requeued = service.reEnqueueStuckOrder(orderId);

        assertThat(requeued).isFalse();
        verifyNoInteractions(outboxProcessor);
    }

    @Test
    void reEnqueueChoosesPartialSyncWhenLastOpWasPartial() {
        order.setErpSyncStatus("PENDING_SYNC");
        order.setLastSyncOp("STOCK_PARTIAL");
        when(orderRepo.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(delivery));

        service.reEnqueueStuckOrder(orderId);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(outboxProcessor).enqueue(eq("ERP_SYNC_STOCK"), captor.capture());
        assertThat(captor.getValue()).containsEntry("isPartial", true);
    }
}
