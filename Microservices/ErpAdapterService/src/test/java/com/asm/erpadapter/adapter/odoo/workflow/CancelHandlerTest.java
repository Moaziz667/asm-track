package com.asm.erpadapter.adapter.odoo.workflow;

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
class CancelHandlerTest {

    @Mock
    private OdooJsonRpcClient rpc;

    private CancelHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CancelHandler(rpc);
    }

    @Test
    void cancelSaleOrder_unlocksThenCancels() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true))   // action_unlock
                .thenReturn(Map.of("result", true))   // action_cancel
                .thenReturn(Map.of("result", List.of(Map.of("state", "cancel"))));  // read state

        assertTrue(handler.cancelSaleOrder(10));
        verify(rpc, times(3)).callRpc(anyList());
    }

    @Test
    void cancelSaleOrder_returnsFalseOnError() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true))   // action_unlock
                .thenReturn(Map.of("error", "user error"));  // action_cancel

        assertFalse(handler.cancelSaleOrder(10));
    }

    @Test
    void cancelSaleOrder_returnsFalseWhenStateNotCancel() {
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true))   // action_unlock
                .thenReturn(Map.of("result", true))   // action_cancel
                .thenReturn(Map.of("result", List.of(Map.of("state", "sale"))));  // still sale

        assertFalse(handler.cancelSaleOrder(10));
    }
}
