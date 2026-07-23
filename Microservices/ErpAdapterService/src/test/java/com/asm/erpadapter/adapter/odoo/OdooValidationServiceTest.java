package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.adapter.odoo.workflow.DeliveryValidationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OdooValidationServiceTest {

    @Mock
    private OdooJsonRpcClient rpc;
    @Mock
    private OdooPickingService pickingService;
    @Mock
    private DeliveryValidationHandler wizardHandler;

    private OdooValidationService service;

    @BeforeEach
    void setUp() {
        service = new OdooValidationService(rpc, pickingService, wizardHandler);
    }

    // ── confirmOrderIfNeeded ──────────────────────────────────────────────────

    @Test
    void confirmOrderIfNeeded_confirmsDraftOrder() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", List.of(Map.of("state", "draft"))))  // read state
                .thenReturn(Map.of("result", true));  // action_confirm

        service.confirmOrderIfNeeded(10);
        verify(rpc, times(2)).callRpc(anyList());
    }

    @Test
    void confirmOrderIfNeeded_confirmsSentOrder() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", List.of(Map.of("state", "sent"))))
                .thenReturn(Map.of("result", true));

        service.confirmOrderIfNeeded(10);
        verify(rpc, times(2)).callRpc(anyList());
    }

    @Test
    void confirmOrderIfNeeded_skipsConfirmedOrder() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", List.of(Map.of("state", "sale"))));

        service.confirmOrderIfNeeded(10);
        verify(rpc, times(1)).callRpc(anyList());
    }

    // ── validateTransferByPickingId ───────────────────────────────────────────

    @Test
    void validateTransferByPickingId_returnsTrueWhenAlreadyDone() {
        when(pickingService.findPickingById(42))
                .thenReturn(Map.of("id", 42, "state", "done"));

        assertTrue(service.validateTransferByPickingId(42));
    }

    @Test
    void validateTransferByPickingId_returnsFalseWhenNotFound() {
        when(pickingService.findPickingById(42)).thenReturn(null);

        assertFalse(service.validateTransferByPickingId(42));
    }

    // ── doValidateTransfer delegates to wizardHandler ─────────────────────────

    @Test
    void doValidateTransfer_delegatesWizardToHandler() {
        when(pickingService.findPickingById(10))
                .thenReturn(Map.of("id", 10, "state", "assigned"));
        when(pickingService.readPickingState(10)).thenReturn("assigned");
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true))   // action_assign
                .thenReturn(Map.of("result", true))   // action_set_quantities
                .thenReturn(Map.of("result", List.of(Map.of("id", 1, "state", "assigned"))))  // search_read moves
                .thenReturn(Map.of("result", Map.of("res_model", "stock.immediate.transfer", "res_id", 99)));  // button_validate
        when(pickingService.readPickingState(10)).thenReturn("done");

        assertTrue(service.validateTransferByPickingId(10));
        verify(wizardHandler).handleWizard(eq(10), anyMap());
    }

    // ── readSaleOrderState ────────────────────────────────────────────────────

    @Test
    void readSaleOrderState_delegatesToRpc() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("state", "draft"))));

        assertEquals("draft", service.readSaleOrderState(10));
    }
}
