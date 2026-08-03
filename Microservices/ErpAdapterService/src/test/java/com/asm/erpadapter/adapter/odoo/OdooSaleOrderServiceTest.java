package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.adapter.odoo.workflow.CancelHandler;
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
class OdooSaleOrderServiceTest {

    @Mock
    private OdooJsonRpcClient rpc;
    @Mock
    private CancelHandler cancelHandler;

    private OdooSaleOrderService service;

    @BeforeEach
    void setUp() {
        service = new OdooSaleOrderService(rpc, cancelHandler);
    }

    // ── resolveErpId ──────────────────────────────────────────────────────────

    @Test
    void resolveErpId_parsesNumericId() {
        assertEquals(42, service.resolveErpId("42"));
    }

    @Test
    void resolveErpId_searchesByNameForString() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", List.of(55)));

        assertEquals(55, service.resolveErpId("S00004"));
    }

    @Test
    void resolveErpId_returnsNullForBlank() {
        assertNull(service.resolveErpId(""));
        assertNull(service.resolveErpId(null));
    }

    @Test
    void resolveErpId_returnsNullWhenNameNotFound() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", List.of()));

        assertNull(service.resolveErpId("NONEXISTENT"));
    }

    // ── readSaleOrderState ────────────────────────────────────────────────────

    @Test
    void readSaleOrderState_returnsState() {
        when(rpc.readRecordState("sale.order", 10)).thenReturn("sale");

        assertEquals("sale", service.readSaleOrderState(10));
    }

    @Test
    void readSaleOrderState_returnsNullOnEmpty() {
        when(rpc.readRecordState("sale.order", 10)).thenReturn(null);

        assertNull(service.readSaleOrderState(10));
    }

    // ── cancelSaleOrder ───────────────────────────────────────────────────────

    @Test
    void cancelSaleOrder_delegatesToCancelHandler() {
        when(cancelHandler.cancelSaleOrder(10)).thenReturn(true);

        assertTrue(service.cancelSaleOrder(10));
        verify(cancelHandler).cancelSaleOrder(10);
    }

    @Test
    void cancelSaleOrder_returnsFalseWhenHandlerFails() {
        when(cancelHandler.cancelSaleOrder(10)).thenReturn(false);

        assertFalse(service.cancelSaleOrder(10));
    }

    // ── addNoteToSaleOrder ────────────────────────────────────────────────────

    @Test
    void addNoteToSaleOrder_callsCreate() {
        when(rpc.searchRead(anyString(), anyList(), anyList(), anyInt(), any()))
                .thenReturn(List.of(Map.of("res_id", 1)));
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", 1));

        assertDoesNotThrow(() -> service.addNoteToSaleOrder(10, "<b>Test</b>"));
        verify(rpc, atLeastOnce()).callRpc(anyList());
    }

    @Test
    void addNoteToSaleOrder_worksWithoutSubtype() {
        when(rpc.searchRead(anyString(), anyList(), anyList(), anyInt(), any()))
                .thenReturn(List.of());
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", 1));

        assertDoesNotThrow(() -> service.addNoteToSaleOrder(10, "<b>Test</b>"));
    }
}
