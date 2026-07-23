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
class OdooPickingServiceTest {

    @Mock
    private OdooJsonRpcClient rpc;

    private OdooPickingService service;

    @BeforeEach
    void setUp() {
        service = new OdooPickingService(rpc);
    }

    // ── findSinglePicking ─────────────────────────────────────────────────────

    @Test
    void findSinglePicking_returnsPickingWhenFound() {
        when(rpc.callRpcOrThrow(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("id", 42, "state", "assigned"))));

        var result = service.findSinglePicking(100);

        assertNotNull(result);
        assertEquals(42, result.get("id"));
        assertEquals("assigned", result.get("state"));
    }

    @Test
    void findSinglePicking_returnsNullWhenEmpty() {
        when(rpc.callRpcOrThrow(anyList())).thenReturn(Map.of("result", List.of()));

        assertNull(service.findSinglePicking(100));
    }

    // ── findPickingByName ─────────────────────────────────────────────────────

    @Test
    void findPickingByName_returnsPickingWhenFound() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("id", 55, "state", "done"))));

        var result = service.findPickingByName("WH/OUT/00012");

        assertNotNull(result);
        assertEquals(55, result.get("id"));
    }

    @Test
    void findPickingByName_returnsNullWhenNotFound() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", List.of()));

        assertNull(service.findPickingByName("WH/OUT/99999"));
    }

    @Test
    void findPickingByName_returnsNullOnNullResponse() {
        when(rpc.callRpc(anyList())).thenReturn(null);

        assertNull(service.findPickingByName("WH/OUT/00012"));
    }

    // ── findBackorderPickingId ────────────────────────────────────────────────

    @Test
    void findBackorderPickingId_returnsIdWhenFound() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("id", 77))));

        assertEquals(77, service.findBackorderPickingId(42));
    }

    @Test
    void findBackorderPickingId_returnsNullWhenEmpty() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", List.of()));

        assertNull(service.findBackorderPickingId(42));
    }

    // ── readPickingState ──────────────────────────────────────────────────────

    @Test
    void readPickingState_returnsState() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("state", "done"))));

        assertEquals("done", service.readPickingState(10));
    }

    @Test
    void readPickingState_returnsNullOnEmpty() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", List.of()));

        assertNull(service.readPickingState(10));
    }

    // ── readPickingName ───────────────────────────────────────────────────────

    @Test
    void readPickingName_returnsName() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("name", "WH/OUT/00013"))));

        assertEquals("WH/OUT/00013", service.readPickingName(10));
    }

    @Test
    void readPickingName_returnsNullForNullId() {
        assertNull(service.readPickingName(null));
    }

    // ── hasDonePicking ────────────────────────────────────────────────────────

    @Test
    void hasDonePicking_returnsTrueWhenFound() {
        when(rpc.callRpcOrThrow(anyList())).thenReturn(Map.of("result",
                List.of(Map.of("id", 1))));

        assertTrue(service.hasDonePicking(100));
    }

    @Test
    void hasDonePicking_returnsFalseWhenEmpty() {
        when(rpc.callRpcOrThrow(anyList())).thenReturn(Map.of("result", List.of()));

        assertFalse(service.hasDonePicking(100));
    }

    // ── cancelPicking ─────────────────────────────────────────────────────────

    @Test
    void cancelPicking_returnsTrueOnCancelState() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true))
                .thenReturn(Map.of("result", List.of(Map.of("state", "cancel"))));

        assertTrue(service.cancelPicking(10));
    }

    @Test
    void cancelPicking_returnsFalseOnError() {
        when(rpc.callRpc(anyList())).thenReturn(Map.of("error", "access denied"));

        assertFalse(service.cancelPicking(10));
    }

    @Test
    void cancelPicking_returnsTrueOnDoneState() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true))
                .thenReturn(Map.of("result", List.of(Map.of("state", "done"))));

        assertTrue(service.cancelPicking(10));
    }
}
