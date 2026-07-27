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
        when(capabilityResolver.resolveOptional(CanonicalCapability.SET_FULL_QUANTITY))
                .thenReturn(java.util.Optional.of("action_set_quantities_to_reservation"));
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

    // ── SET_FULL_QUANTITY fallback (Odoo 19 removed the button) ───────────────

    /**
     * Odoo 19 has no {@code action_set_quantities_to_reservation}. Treating that as fatal
     * dead-lettered every full delivery; the reserved quantities are already on the lines, so
     * flagging them picked reaches the same end state.
     */
    @Test
    void setFullQuantities_marksLinesPickedWhenButtonIsAbsent() {
        when(capabilityResolver.resolveOptional(CanonicalCapability.SET_FULL_QUANTITY))
                .thenReturn(java.util.Optional.empty());
        when(capabilityResolver.resolveOptional(CanonicalCapability.MARK_PICKED))
                .thenReturn(java.util.Optional.of("picked"));
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", List.of(442, 443)))  // search move lines
                .thenReturn(Map.of("result", true));              // write picked=true

        service.setFullQuantityDoneOnMoveLines(388);

        verify(rpc).buildArgs(eq("stock.move.line"), eq("write"),
                eq(List.of(List.of(442, 443), Map.of("picked", true))));
    }

    /** Odoo 16 has neither the button nor {@code picked}; reserved quantities already suffice. */
    @Test
    void setFullQuantities_noOpWhenNeitherCapabilityExists() {
        when(capabilityResolver.resolveOptional(CanonicalCapability.SET_FULL_QUANTITY))
                .thenReturn(java.util.Optional.empty());
        when(capabilityResolver.resolveOptional(CanonicalCapability.MARK_PICKED))
                .thenReturn(java.util.Optional.empty());

        service.setFullQuantityDoneOnMoveLines(388);

        verify(rpc, never()).callRpc(anyList());
    }

    @Test
    void setFullQuantities_skipsWriteWhenPickingHasNoMoveLines() {
        when(capabilityResolver.resolveOptional(CanonicalCapability.SET_FULL_QUANTITY))
                .thenReturn(java.util.Optional.empty());
        when(capabilityResolver.resolveOptional(CanonicalCapability.MARK_PICKED))
                .thenReturn(java.util.Optional.of("picked"));
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", List.of()));

        service.setFullQuantityDoneOnMoveLines(388);

        verify(rpc, never()).buildArgs(eq("stock.move.line"), eq("write"), anyList());
    }

    // ── readSaleOrderState ────────────────────────────────────────────────────

    @Test
    void readSaleOrderState_delegatesToSaleOrderService() {
        when(saleOrderService.readSaleOrderState(10)).thenReturn("draft");

        assertEquals("draft", service.readSaleOrderState(10));
        verify(saleOrderService).readSaleOrderState(10);
    }
}
