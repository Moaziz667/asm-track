package com.asm.erpadapter.adapter.odoo.workflow;

import com.asm.erpadapter.adapter.odoo.CanonicalCapability;
import com.asm.erpadapter.adapter.odoo.CapabilityResolver;
import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
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
class ReturnHandlerTest {

    @Mock
    private OdooJsonRpcClient rpc;
    @Mock
    private CapabilityResolver capabilityResolver;

    private ReturnHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReturnHandler(rpc, capabilityResolver);
    }

    @Test
    void callCreateReturns_triesOdoo18MethodFirst() {
        when(capabilityResolver.getCandidates(CanonicalCapability.CREATE_RETURN))
                .thenReturn(List.of("action_create_returns", "create_returns"));
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", Map.of("res_id", 101)));  // Odoo 18+ success

        Map<String, Object> result = handler.callCreateReturns(50, Map.of());

        assertNotNull(result);
        verify(rpc, times(1)).callRpc(anyList());
    }

    @Test
    void callCreateReturns_fallsBackToOdoo16Method() {
        when(capabilityResolver.getCandidates(CanonicalCapability.CREATE_RETURN))
                .thenReturn(List.of("action_create_returns", "create_returns"));
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", "no method"))          // Odoo 18 fails
                .thenReturn(Map.of("result", Map.of("res_id", 102)));  // Odoo 16 success

        Map<String, Object> result = handler.callCreateReturns(50, Map.of());

        assertNotNull(result);
        verify(rpc, times(2)).callRpc(anyList());
    }

    @Test
    void callCreateReturns_returnsLastErrorIfAllFail() {
        when(capabilityResolver.getCandidates(CanonicalCapability.CREATE_RETURN))
                .thenReturn(List.of("action_create_returns", "create_returns"));
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", "method 1 failed"))
                .thenReturn(Map.of("error", "method 2 failed"));

        Map<String, Object> result = handler.callCreateReturns(50, Map.of());

        assertNotNull(result);
        assertTrue(((Map<?, ?>) result).containsKey("error"));
        verify(rpc, times(2)).callRpc(anyList());
    }

    @Test
    void callCreateReturns_singleCandidate() {
        when(capabilityResolver.getCandidates(CanonicalCapability.CREATE_RETURN))
                .thenReturn(List.of("create_returns"));
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", 103));

        Map<String, Object> result = handler.callCreateReturns(50, Map.of());

        assertNotNull(result);
        verify(rpc, times(1)).callRpc(anyList());
    }
}
