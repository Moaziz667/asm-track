package com.asm.erpadapter.adapter.odoo;

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
    private OdooCapabilities caps;

    private OdooValidationService service;

    @BeforeEach
    void setUp() {
        service = new OdooValidationService(rpc, pickingService, caps);
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

    // ── handleWizard ──────────────────────────────────────────────────────────

    @Test
    void handleWizard_dispatchesImmediateTransfer() {
        Map<?, ?> res = Map.of("res_model", "stock.immediate.transfer", "res_id", 99);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));

        assertDoesNotThrow(() -> service.handleWizard(42, res));
    }

    @Test
    void handleWizard_dispatchesBackorder() {
        Map<?, ?> res = Map.of("res_model", "stock.backorder.confirmation", "res_id", 88);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));

        assertDoesNotThrow(() -> service.handleWizard(42, res));
    }

    @Test
    void handleWizard_dispatchesSms() {
        Map<?, ?> res = Map.of("res_model", "confirm.stock.sms", "res_id", 77);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));

        assertDoesNotThrow(() -> service.handleWizard(42, res));
    }

    // ── readSaleOrderState ────────────────────────────────────────────────────

    @Test
    void readSaleOrderState_delegatesToRpc() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("state", "draft"))));

        assertEquals("draft", service.readSaleOrderState(10));
    }
}
