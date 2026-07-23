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
    @Mock
    private OdooSaleOrderService saleOrderService;
    @Mock
    private CapabilityResolver capabilityResolver;

    private OdooValidationService service;

    @BeforeEach
    void setUp() {
        service = new OdooValidationService(rpc, pickingService, wizardHandler, saleOrderService, capabilityResolver);
    }

    // ── confirmOrderIfNeeded ──────────────────────────────────────────────────

    @Test
    void confirmOrderIfNeeded_confirmsDraftOrder() {
        when(saleOrderService.readSaleOrderState(10)).thenReturn("draft");
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));  // action_confirm

        service.confirmOrderIfNeeded(10);
        verify(saleOrderService).readSaleOrderState(10);
        verify(rpc, times(1)).callRpc(anyList());
    }

    @Test
    void confirmOrderIfNeeded_confirmsSentOrder() {
        when(saleOrderService.readSaleOrderState(10)).thenReturn("sent");
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));

        service.confirmOrderIfNeeded(10);
        verify(rpc, times(1)).callRpc(anyList());
    }

    @Test
    void confirmOrderIfNeeded_skipsConfirmedOrder() {
        when(saleOrderService.readSaleOrderState(10)).thenReturn("sale");

        service.confirmOrderIfNeeded(10);
        verify(rpc, never()).callRpc(anyList());
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
        when(capabilityResolver.resolve(CanonicalCapability.RESERVE_STOCK)).thenReturn("action_assign");
        when(capabilityResolver.resolve(CanonicalCapability.SET_FULL_QUANTITY)).thenReturn("action_set_quantities_to_reservation");
        when(capabilityResolver.resolve(CanonicalCapability.DELIVERY_VALIDATE)).thenReturn("button_validate");
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true))   // action_assign
                .thenReturn(Map.of("result", true))   // action_set_quantities
                .thenReturn(Map.of("result", List.of(Map.of("id", 1, "state", "assigned"))))  // search_read moves
                .thenReturn(Map.of("result", Map.of("res_model", "stock.immediate.transfer", "res_id", 99)));  // button_validate
        when(pickingService.readPickingState(10)).thenReturn("assigned").thenReturn("done");

        assertTrue(service.validateTransferByPickingId(10));
        verify(wizardHandler).handleWizard(eq(10), anyMap());
    }

    // ── readSaleOrderState ────────────────────────────────────────────────────

    @Test
    void readSaleOrderState_delegatesToSaleOrderService() {
        when(saleOrderService.readSaleOrderState(10)).thenReturn("draft");

        assertEquals("draft", service.readSaleOrderState(10));
        verify(saleOrderService).readSaleOrderState(10);
    }
}
