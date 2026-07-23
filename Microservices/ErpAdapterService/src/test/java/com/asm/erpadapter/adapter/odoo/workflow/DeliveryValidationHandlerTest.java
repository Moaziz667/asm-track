package com.asm.erpadapter.adapter.odoo.workflow;

import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import com.asm.erpadapter.adapter.odoo.OdooWorkflowException;
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
class DeliveryValidationHandlerTest {

    @Mock
    private OdooJsonRpcClient rpc;

    private DeliveryValidationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DeliveryValidationHandler(rpc);
    }

    // ── handleWizard dispatch ─────────────────────────────────────────────────

    @Test
    void handleWizard_dispatchesImmediateTransfer() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));
        Map<?, ?> res = Map.of("res_model", "stock.immediate.transfer", "res_id", 99);

        assertDoesNotThrow(() -> handler.handleWizard(42, res));
    }

    @Test
    void handleWizard_dispatchesBackorder() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));
        Map<?, ?> res = Map.of("res_model", "stock.backorder.confirmation", "res_id", 88);

        assertDoesNotThrow(() -> handler.handleWizard(42, res));
    }

    @Test
    void handleWizard_dispatchesSms() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));
        Map<?, ?> res = Map.of("res_model", "confirm.stock.sms", "res_id", 77);

        assertDoesNotThrow(() -> handler.handleWizard(42, res));
    }

    @Test
    void handleWizard_throwsOnUnknownModel() {
        Map<?, ?> res = Map.of("res_model", "unknown.wizard", "res_id", 1);

        OdooWorkflowException ex = assertThrows(OdooWorkflowException.class,
                () -> handler.handleWizard(42, res));
        assertTrue(ex.getMessage().contains("unknown.wizard"));
    }

    // ── confirmSmsWizard ──────────────────────────────────────────────────────

    @Test
    void confirmSmsWizard_triesActionConfirmFirst() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));
        Map<?, ?> res = Map.of("res_id", 77);

        handler.confirmSmsWizard(res);
        verify(rpc, times(1)).callRpc(anyList());
    }

    @Test
    void confirmSmsWizard_fallsBackToSendAndValidate() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", "no method"))  // action_confirm fails
                .thenReturn(Map.of("result", true));        // action_send_and_validate
        Map<?, ?> res = Map.of("res_id", 77);

        handler.confirmSmsWizard(res);
        verify(rpc, times(2)).callRpc(anyList());
    }

    @Test
    void confirmSmsWizard_handlesRpcException() {
        when(rpc.callRpc(anyList())).thenThrow(new RuntimeException("connection refused"));
        Map<?, ?> res = Map.of("res_id", 77);

        assertDoesNotThrow(() -> handler.confirmSmsWizard(res));
    }

    // ── confirmImmediateTransferWizard ────────────────────────────────────────

    @Test
    void confirmImmediateTransferWizard_callsProcess() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));
        Map<?, ?> res = Map.of("res_id", 99);

        handler.confirmImmediateTransferWizard(res);
        verify(rpc, times(1)).callRpc(anyList());
    }

    @Test
    void confirmImmediateTransferWizard_handlesNullResId() {
        Map<?, ?> res = Map.of("res_model", "stock.immediate.transfer");

        assertDoesNotThrow(() -> handler.confirmImmediateTransferWizard(res));
    }

    // ── confirmBackorderWizard (Odoo 16-17 path) ─────────────────────────────

    @Test
    void confirmBackorderWizard_odoo16_17_callsProcess() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));
        Map<?, ?> res = Map.of("res_id", 88);

        handler.confirmBackorderWizard(res);
        verify(rpc, times(1)).callRpc(anyList());
    }

    // ── confirmBackorderWizard (Odoo 18-19 path) ─────────────────────────────

    @Test
    void confirmBackorderWizard_odoo18_19_createsWizardManually() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", 101))   // create wizard
                .thenReturn(Map.of("result", true));  // process
        Map<?, ?> res = Map.of(
                "context", Map.of("button_validate_picking_ids", List.of(10, 20))
        );

        handler.confirmBackorderWizard(res);
        verify(rpc, times(2)).callRpc(anyList());
    }

    @Test
    void confirmBackorderWizard_odoo18_19_handlesNoPickingIds() {
        Map<?, ?> res = Map.of("context", Map.of());

        handler.confirmBackorderWizard(res);
        verify(rpc, never()).callRpc(anyList());
    }

    @Test
    void confirmBackorderWizard_odoo18_19_handlesCreateFailure() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", "access denied"));
        Map<?, ?> res = Map.of(
                "context", Map.of("button_validate_picking_ids", List.of(10))
        );

        assertDoesNotThrow(() -> handler.confirmBackorderWizard(res));
    }

    @Test
    void confirmBackorderWizard_odoo16_17_handlesRpcException() {
        when(rpc.callRpc(anyList())).thenThrow(new RuntimeException("connection refused"));
        Map<?, ?> res = Map.of("res_id", 88);

        assertDoesNotThrow(() -> handler.confirmBackorderWizard(res));
    }
}
