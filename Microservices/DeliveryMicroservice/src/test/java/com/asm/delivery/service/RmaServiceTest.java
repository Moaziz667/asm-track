package com.asm.delivery.service;

import com.asm.delivery.dto.request.CreateRmaRequest;
import com.asm.delivery.dto.response.RmaResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RmaRepository;
import com.asm.delivery.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RmaServiceTest {

    @Mock RmaRepository rmaRepository;
    @Mock com.asm.delivery.repository.RmaPhotoRepository rmaPhotoRepository;
    @Mock com.asm.delivery.repository.RmaStatusHistoryRepository rmaStatusHistoryRepository;
    @Mock DeliveryRepository deliveryRepository;
    @Mock OutboxProcessor outboxProcessor;
    @Mock AuditLogService auditLogService;
    @Mock EventPublisher eventPublisher;

    @InjectMocks RmaService service;

    private UUID deliveryId;
    private UUID orderId;
    private Delivery delivery;
    private Order order;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        deliveryId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        order = Order.builder()
                .id(orderId)
                .erpOrderId("ERP-001")
                .clientName("Client Test")
                .blNumber("BL-001")
                .items(new ArrayList<>())
                .build();

        delivery = Delivery.builder()
                .id(deliveryId)
                .order(order)
                .status(DeliveryStatus.DELIVERED)
                .blNumber("BL-001")
                .build();

        principal = new UserPrincipal(UUID.randomUUID().toString(), "ADMIN", "Test User", null, null);
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("happy path — returns requested qty")
        void create_happyPath() {
            addOrderItem("SKU-1", 5, 4); // ordered 5, delivered 4
            stubDeliveryLookup();
            stubNoOpenReturn();
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            CreateRmaRequest req = buildRequest("SKU-1", 3);
            RmaResponse resp = service.create(req, principal);

            assertThat(resp).isNotNull();
            ArgumentCaptor<Rma> captor = ArgumentCaptor.forClass(Rma.class);
            verify(rmaRepository).save(captor.capture());
            Rma saved = captor.getValue();
            assertThat(saved.getItems()).hasSize(1);
            assertThat(saved.getItems().get(0).getQuantity()).isEqualTo(3);
            assertThat(saved.getItems().get(0).getSku()).isEqualTo("SKU-1");
        }

        @Test
        @DisplayName("clamps to delivered qty when requesting more")
        void create_clampsToDelivered() {
            addOrderItem("SKU-1", 5, 2); // delivered 2
            stubDeliveryLookup();
            stubNoOpenReturn();
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            CreateRmaRequest req = buildRequest("SKU-1", 5);
            RmaResponse resp = service.create(req, principal);

            ArgumentCaptor<Rma> captor = ArgumentCaptor.forClass(Rma.class);
            verify(rmaRepository).save(captor.capture());
            assertThat(captor.getValue().getItems().get(0).getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("alreadyReturned subtraction — prior RECEIVED RMA reduces returnable")
        void create_subtractsPriorReturned() {
            addOrderItem("SKU-1", 5, 4);
            stubDeliveryLookup();
            stubNoOpenReturn();
            // prior RMA returned 2 units — overrides the empty stub from stubNoOpenReturn
            when(rmaRepository.sumReturnedQtyBySku(deliveryId))
                    .thenReturn(List.<Object[]>of(new Object[]{"SKU-1", 2L}));
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            CreateRmaRequest req = buildRequest("SKU-1", 4);
            RmaResponse resp = service.create(req, principal);

            ArgumentCaptor<Rma> captor = ArgumentCaptor.forClass(Rma.class);
            verify(rmaRepository).save(captor.capture());
            // returnable = 4 delivered - 2 returned = 2
            assertThat(captor.getValue().getItems().get(0).getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("REJECTED prior RMA does not reduce returnable")
        void create_rejectedDoesNotCount() {
            addOrderItem("SKU-1", 5, 4);
            stubDeliveryLookup();
            stubNoOpenReturn();
            // prior RMA is REJECTED — not in sumReturnedQtyBySku filter (already empty from stubNoOpenReturn)
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            CreateRmaRequest req = buildRequest("SKU-1", 4);
            RmaResponse resp = service.create(req, principal);

            ArgumentCaptor<Rma> captor = ArgumentCaptor.forClass(Rma.class);
            verify(rmaRepository).save(captor.capture());
            assertThat(captor.getValue().getItems().get(0).getQuantity()).isEqualTo(4);
        }

        @Test
        @DisplayName("blocks when an open return already exists")
        void create_blocksOpenReturn() {
            addOrderItem("SKU-1", 5, 4);
            stubDeliveryLookup();
            Rma existing = Rma.builder().id(UUID.randomUUID()).status(RmaStatus.REQUESTED).build();
            when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId))
                    .thenReturn(List.of(existing));

            CreateRmaRequest req = buildRequest("SKU-1", 2);
            assertThatThrownBy(() -> service.create(req, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("déjà en cours");
        }

        @Test
        @DisplayName("allows new RMA after terminal RESTOCKED")
        void create_allowsAfterRestocked() {
            addOrderItem("SKU-1", 5, 5); // 5 delivered
            stubDeliveryLookup();
            // prior RESTOCKED — terminal, not open
            Rma prior = Rma.builder().id(UUID.randomUUID()).status(RmaStatus.RESTOCKED).build();
            when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId))
                    .thenReturn(List.of(prior));
            // prior RESTOCKED returned 3 of 5 — overrides the empty stub
            when(rmaRepository.sumReturnedQtyBySku(deliveryId))
                    .thenReturn(List.<Object[]>of(new Object[]{"SKU-1", 3L}));
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            CreateRmaRequest req = buildRequest("SKU-1", 1);
            RmaResponse resp = service.create(req, principal);
            assertThat(resp).isNotNull();
        }

        @Test
        @DisplayName("blocks non-returnable delivery status")
        void create_blocksNonReturnableStatus() {
            delivery.setStatus(DeliveryStatus.SCHEDULED);
            stubDeliveryLookup();

            CreateRmaRequest req = buildRequest("SKU-1", 1);
            assertThatThrownBy(() -> service.create(req, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("retour");
        }

        @Test
        @DisplayName("blocks empty items list")
        void create_blocksEmptyItems() {
            stubDeliveryLookup();
            CreateRmaRequest req = new CreateRmaRequest();
            req.setDeliveryId(deliveryId);
            req.setItems(List.of());
            assertThatThrownBy(() -> service.create(req, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("retour");
        }

        @Test
        @DisplayName("all items skipped → RMA_EMPTY")
        void create_allItemsSkipped() {
            // quantityDone=0 → delivered=0 → returnable=0 → all skipped
            addOrderItem("SKU-1", 5, 0);
            stubDeliveryLookup();
            stubNoOpenReturn();

            CreateRmaRequest req = buildRequest("SKU-1", 1);
            assertThatThrownBy(() -> service.create(req, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("quantité valide");
        }

        // ── helpers ──

        private void addOrderItem(String sku, int qty, int qtyDone) {
            order.getItems().add(OrderItem.builder()
                    .sku(sku).name("Item " + sku)
                    .quantity(qty).quantityDone(qtyDone)
                    .unitPrice(BigDecimal.TEN)
                    .build());
        }

        private void stubDeliveryLookup() {
            when(deliveryRepository.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(delivery));
        }

        private void stubNoOpenReturn() {
            when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId)).thenReturn(List.of());
            when(rmaRepository.sumReturnedQtyBySku(deliveryId)).thenReturn(List.of());
        }

        private CreateRmaRequest buildRequest(String sku, int qty) {
            CreateRmaRequest req = new CreateRmaRequest();
            req.setDeliveryId(deliveryId);
            req.setReason("defective");
            CreateRmaRequest.Item item = new CreateRmaRequest.Item();
            item.setSku(sku);
            item.setName("Item " + sku);
            item.setQuantity(qty);
            item.setCondition(RmaItemCondition.RESELLABLE);
            req.setItems(List.of(item));
            return req;
        }
    }

    // ── transition() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("transition")
    class Transition {

        @Test
        @DisplayName("REQUESTED → APPROVED")
        void transition_approved() {
            Rma rma = rma(RmaStatus.REQUESTED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            RmaResponse resp = service.transition(rma.getId(), RmaStatus.APPROVED, null, principal);

            assertThat(rma.getStatus()).isEqualTo(RmaStatus.APPROVED);
            verify(rmaRepository).save(rma);
        }

        @Test
        @DisplayName("APPROVED → RECEIVED sets receivedAt")
        void transition_received() {
            Rma rma = rma(RmaStatus.APPROVED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            service.transition(rma.getId(), RmaStatus.RECEIVED, null, principal);

            assertThat(rma.getReceivedAt()).isNotNull();
        }

        @Test
        @DisplayName("RECEIVED → RESTOCKED sets erpSyncStatus + enqueues ERP_SYNC_RETURN")
        void transition_restocked_enqueuesErpReturn() {
            Rma rma = rma(RmaStatus.RECEIVED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            service.transition(rma.getId(), RmaStatus.RESTOCKED, null, principal);

            assertThat(rma.getErpSyncStatus()).isEqualTo("PENDING_SYNC");
            assertThat(rma.getRestockedAt()).isNotNull();
            verify(outboxProcessor).enqueue(eq("ERP_SYNC_RETURN"), any());
        }

        @Test
        @DisplayName("REJECTED from REQUESTED — requires note")
        void transition_rejected_requiresNote() {
            Rma rma = rma(RmaStatus.REQUESTED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));

            assertThatThrownBy(() -> service.transition(rma.getId(), RmaStatus.REJECTED, null, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("motif");
        }

        @Test
        @DisplayName("REJECTED from REQUESTED with note → ok")
        void transition_rejected_withNote() {
            Rma rma = rma(RmaStatus.REQUESTED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            RmaResponse resp = service.transition(rma.getId(), RmaStatus.REJECTED, "not eligible", principal);

            assertThat(rma.getStatus()).isEqualTo(RmaStatus.REJECTED);
            assertThat(rma.getResolutionNote()).isEqualTo("not eligible");
        }

        @Test
        @DisplayName("CANCELLED from APPROVED — requires note")
        void transition_cancelled_requiresNote() {
            Rma rma = rma(RmaStatus.APPROVED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));

            assertThatThrownBy(() -> service.transition(rma.getId(), RmaStatus.CANCELLED, null, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("motif");
        }

        @Test
        @DisplayName("blocks transition from terminal RESTOCKED")
        void transition_blocksFromRestocked() {
            Rma rma = rma(RmaStatus.RESTOCKED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));

            assertThatThrownBy(() -> service.transition(rma.getId(), RmaStatus.APPROVED, null, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("clôturé");
        }

        @Test
        @DisplayName("blocks transition from terminal REJECTED")
        void transition_blocksFromRejected() {
            Rma rma = rma(RmaStatus.REJECTED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));

            assertThatThrownBy(() -> service.transition(rma.getId(), RmaStatus.APPROVED, null, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("clôturé");
        }

        @Test
        @DisplayName("blocks invalid forward jump REQUESTED → RECEIVED")
        void transition_invalidJump() {
            Rma rma = rma(RmaStatus.REQUESTED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));

            assertThatThrownBy(() -> service.transition(rma.getId(), RmaStatus.RECEIVED, null, principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("Transition invalide");
        }
    }

    // ── resync() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resync")
    class Resync {

        @Test
        @DisplayName("happy path — RESTOCKED + SYNC_FAILED → re-enqueues")
        void resync_happyPath() {
            Rma rma = rma(RmaStatus.RESTOCKED);
            rma.setErpSyncStatus("SYNC_FAILED");
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));
            when(rmaRepository.save(any())).thenAnswer(inv -> {
                Rma r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });

            service.resync(rma.getId(), principal);

            assertThat(rma.getErpSyncStatus()).isEqualTo("PENDING_SYNC");
            verify(outboxProcessor).enqueue(eq("ERP_SYNC_RETURN"), any());
        }

        @Test
        @DisplayName("blocks non-RESTOCKED")
        void resync_blocksNonRestocked() {
            Rma rma = rma(RmaStatus.RECEIVED);
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));

            assertThatThrownBy(() -> service.resync(rma.getId(), principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("restocké");
        }

        @Test
        @DisplayName("blocks non-SYNC_FAILED")
        void resync_blocksNonFailed() {
            Rma rma = rma(RmaStatus.RESTOCKED);
            rma.setErpSyncStatus("SYNCED");
            when(rmaRepository.findById(rma.getId())).thenReturn(Optional.of(rma));

            assertThatThrownBy(() -> service.resync(rma.getId(), principal))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("échec");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Rma rma(RmaStatus status) {
        return Rma.builder()
                .id(UUID.randomUUID())
                .deliveryId(deliveryId)
                .orderId(orderId)
                .erpOrderId("ERP-001")
                .blNumber("BL-001")
                .clientName("Client Test")
                .status(status)
                .items(new ArrayList<>())
                .build();
    }
}
