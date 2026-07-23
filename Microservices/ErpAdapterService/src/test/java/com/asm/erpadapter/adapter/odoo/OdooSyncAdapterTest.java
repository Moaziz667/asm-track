package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.adapter.odoo.workflow.ReturnHandler;
import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPodDTO;
import com.asm.erpadapter.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OdooSyncAdapterTest {

    @Mock
    private OdooJsonRpcClient rpc;
    @Mock
    private IdempotencyService idempotency;
    @Mock
    private OdooPickingService pickingService;
    @Mock
    private OdooValidationService validationService;
    @Mock
    private OdooSaleOrderService saleOrderService;
    @Mock
    private OdooProductService productService;
    @Mock
    private OdooPodService podService;
    @Mock
    private ReturnHandler returnHandler;

    private OdooSyncAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        adapter = new OdooSyncAdapter(rpc, idempotency,
                pickingService, validationService, saleOrderService, productService, podService, returnHandler);

        // Default: idempotency.execute runs the supplier directly
        lenient().when(idempotency.execute(anyString(), anyString(), any(Class.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(3);
                    return supplier.get();
                });
    }

    // ── syncFullDelivery ──────────────────────────────────────────────────────

    @Test
    void syncFullDelivery_withBackorderPickingId_validatesDirectly() {
        when(validationService.validateTransferByPickingId(42)).thenReturn(true);

        boolean result = adapter.syncFullDelivery("100", 42, "tx-1", null);

        assertTrue(result);
        verify(validationService).validateTransferByPickingId(42);
    }

    @Test
    void syncFullDelivery_withPickingRef_findsByName() {
        when(pickingService.findPickingByName("WH/OUT/00012"))
                .thenReturn(Map.of("id", 55, "state", "assigned"));
        when(validationService.validateTransferByPickingId(55)).thenReturn(true);

        boolean result = adapter.syncFullDelivery("100", null, "tx-1", "WH/OUT/00012");

        assertTrue(result);
        verify(validationService).validateTransferByPickingId(55);
    }

    @Test
    void syncFullDelivery_fallbackToSaleOrder() {
        when(saleOrderService.resolveErpId("100")).thenReturn(10);
        when(validationService.validateTransfer(10, null)).thenReturn(true);

        boolean result = adapter.syncFullDelivery("100", null, "tx-1", null);

        assertTrue(result);
        verify(validationService).validateTransfer(10, null);
    }

    @Test
    void syncFullDelivery_returnsFalseWhenErpIdNull() {
        when(saleOrderService.resolveErpId("100")).thenReturn(null);

        assertFalse(adapter.syncFullDelivery("100", null, "tx-1", null));
    }

    // ── syncOrderCancellation ─────────────────────────────────────────────────

    @Test
    void syncOrderCancellation_withPickingRef_cancelsPicking() {
        when(pickingService.findPickingByName("WH/OUT/00012"))
                .thenReturn(Map.of("id", 55));
        when(pickingService.cancelPicking(55)).thenReturn(true);

        assertTrue(adapter.syncOrderCancellation("100", "tx-1", "WH/OUT/00012"));
        verify(pickingService).cancelPicking(55);
    }

    @Test
    void syncOrderCancellation_withoutPickingRef_cancelsSaleOrder() {
        when(saleOrderService.resolveErpId("100")).thenReturn(10);
        when(saleOrderService.cancelSaleOrder(10)).thenReturn(true);

        assertTrue(adapter.syncOrderCancellation("100", "tx-1", null));
        verify(saleOrderService).cancelSaleOrder(10);
    }

    // ── syncFailure ───────────────────────────────────────────────────────────

    @Test
    void syncFailure_postsNoteAndTag() {
        when(saleOrderService.resolveErpId("100")).thenReturn(10);

        assertTrue(adapter.syncFailure("100", "CLIENT_ABSENT", "Not home", "tx-1", null));
        verify(saleOrderService).addNoteToSaleOrder(eq(10), contains("CLIENT_ABSENT"));
        verify(saleOrderService).tagOrderAsDeliveryFailed(10);
    }

    @Test
    void syncFailure_returnsFalseWhenErpIdNull() {
        when(saleOrderService.resolveErpId("100")).thenReturn(null);

        assertFalse(adapter.syncFailure("100", "DAMAGED", null, "tx-1", null));
    }

    // ── syncReschedule ────────────────────────────────────────────────────────

    @Test
    void syncReschedule_writesCommitmentDate() {
        when(saleOrderService.resolveErpId("100")).thenReturn(10);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));

        assertTrue(adapter.syncReschedule("100", "2026-06-11T08:00", "tx-1", null));
        verify(rpc).callRpc(anyList());
        verify(saleOrderService).addNoteToSaleOrder(eq(10), contains("Replanification"));
    }

    // ── syncProofOfDelivery ───────────────────────────────────────────────────

    @Test
    void syncProofOfDelivery_delegatesToPodService() {
        when(saleOrderService.resolveErpId("100")).thenReturn(10);
        when(podService.syncProofOfDelivery(eq(10), any())).thenReturn(true);

        ErpPodDTO pod = ErpPodDTO.builder().recipientName("John").build();
        assertTrue(adapter.syncProofOfDelivery("100", pod, "tx-1", null));
        verify(podService).syncProofOfDelivery(10, pod);
    }

    // ── syncPartialDelivery ───────────────────────────────────────────────────

    @Test
    void syncPartialDelivery_returnsFalseWhenNoPicking() {
        when(saleOrderService.resolveErpId("100")).thenReturn(10);
        when(pickingService.findSinglePicking(10)).thenReturn(null);

        ErpPartialDeliveryResultDTO result = adapter.syncPartialDelivery("100",
                List.of(), "tx-1", null);

        assertFalse(result.isSuccess());
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void syncFullDelivery_respectsIdempotencyCache() {
        when(idempotency.execute(anyString(), anyString(), any(Class.class), any(Supplier.class)))
                .thenReturn(false);

        boolean result = adapter.syncFullDelivery("100", 42, "tx-1", null);

        assertFalse(result);
        verifyNoInteractions(validationService);
    }
}
