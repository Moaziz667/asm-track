package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MethodResolverTest {

    @Mock private OdooJsonRpcClient rpc;
    @Mock private CapabilityRegistry registry;

    private MethodResolver resolver;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new MethodResolver(rpc, registry);
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void resolve_returnsFirstCallableMethod() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", List.of("action_create_returns", "create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(entry);
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", Map.of("res_id", 101)));

        String result = resolver.resolve("CREATE_RETURN");

        assertEquals("action_create_returns", result);
    }

    @Test
    void resolve_triesNextMethod_whenFirstFails() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", List.of("action_create_returns", "create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(entry);
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", Map.of("data", Map.of("message", "object has no attribute 'action_create_returns'"))))
                .thenReturn(Map.of("result", Map.of("res_id", 102)));

        String result = resolver.resolve("CREATE_RETURN");

        assertEquals("create_returns", result);
        verify(rpc, times(2)).callRpc(anyList());
    }

    @Test
    void resolve_throwsMethodResolutionException_whenAllFail() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", List.of("action_create_returns", "create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(entry);
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", Map.of("data", Map.of("message", "does not exist"))))
                .thenReturn(Map.of("error", Map.of("data", Map.of("message", "does not exist"))));

        MethodResolver.MethodResolutionException ex = assertThrows(
                MethodResolver.MethodResolutionException.class,
                () -> resolver.resolve("CREATE_RETURN"));

        assertEquals("CREATE_RETURN", ex.getCapability());
        assertEquals("stock.return.picking", ex.getModel());
        assertEquals(2, ex.getCandidates().size());
    }

    @Test
    void resolve_throwsIllegalArgument_forFieldCapability() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(entry);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("DONE_QUANTITY"));
    }

    @Test
    void resolve_considersMethodExists_whenErrorNotAboutMissingMethod() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", List.of("action_create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(entry);
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", Map.of("data", Map.of("message", "Access denied"))));

        String result = resolver.resolve("CREATE_RETURN");

        assertEquals("action_create_returns", result);
    }

    @Test
    void resolveWithOverride_usesOverride_whenCallable() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", List.of("action_create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(entry);
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("result", true));

        String result = resolver.resolveWithOverride("CREATE_RETURN", "custom_create_returns");

        assertEquals("custom_create_returns", result);
    }

    @Test
    void resolveWithOverride_fallsBackToDefault_whenNotCallable() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", List.of("action_create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(entry);
        when(rpc.callRpc(anyList()))
                .thenReturn(Map.of("error", Map.of("data", Map.of("message", "does not exist"))))
                .thenReturn(Map.of("result", Map.of("res_id", 200)));

        String result = resolver.resolveWithOverride("CREATE_RETURN", "nonexistent_method");

        assertEquals("action_create_returns", result);
    }

    @Test
    void getCandidates_delegatesToRegistry() {
        when(registry.getCandidates("CREATE_RETURN"))
                .thenReturn(List.of("action_create_returns", "create_returns"));

        List<String> candidates = resolver.getCandidates("CREATE_RETURN");

        assertEquals(2, candidates.size());
        assertEquals("action_create_returns", candidates.get(0));
        assertEquals("create_returns", candidates.get(1));
    }
}
