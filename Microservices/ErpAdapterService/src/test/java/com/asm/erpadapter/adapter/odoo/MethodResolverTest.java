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
    @Mock private OdooVersionResolver versionResolver;

    private MethodResolver resolver;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new MethodResolver(rpc, registry, versionResolver);
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
                .thenReturn(attributeError("action_create_returns"))
                .thenReturn(attributeError("create_returns"));

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
                .thenReturn(attributeError("nonexistent_method"))
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

    // ── Probe semantics (regression cover for the SET_FULL_QUANTITY failure) ──────────────────

    /** Odoo fault for a method that genuinely does not exist. */
    private static Map<String, Object> attributeError(String method) {
        return Map.of("error", Map.of("data", Map.of(
                "name", "builtins.AttributeError",
                "message", "'stock.picking' object has no attribute '" + method + "'")));
    }

    /**
     * A method that EXISTS but was called on a missing record. Its message contains
     * "does not exist", which the previous substring-matching probe misread as "method absent" —
     * that false negative is what made SET_FULL_QUANTITY unresolvable and dead-lettered every
     * partial delivery. Must resolve normally.
     */
    @Test
    void resolve_treatsMissingErrorAsMethodPresent() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.picking", "METHOD", List.of("action_set_quantities_to_reservation"));
        when(registry.getRequired("SET_FULL_QUANTITY")).thenReturn(entry);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("error", Map.of("data", Map.of(
                "name", "odoo.exceptions.MissingError",
                "message", "Record does not exist or has been deleted."))));

        assertEquals("action_set_quantities_to_reservation", resolver.resolve("SET_FULL_QUANTITY"));
    }

    /** A business-rule refusal also proves the method resolved and ran. */
    @Test
    void resolve_treatsUserErrorAsMethodPresent() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.picking", "METHOD", List.of("button_validate"));
        when(registry.getRequired("DELIVERY_VALIDATE")).thenReturn(entry);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("error", Map.of("data", Map.of(
                "name", "odoo.exceptions.UserError",
                "message", "Nothing to validate."))));

        assertEquals("button_validate", resolver.resolve("DELIVERY_VALIDATE"));
    }

    /**
     * A transport failure must NOT be recorded as "capability unavailable": that verdict gets cached
     * and would disable a working integration until the TTL expires. It is retryable instead.
     */
    @Test
    void resolve_throwsRetryableProbeException_onTransportFailure() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.picking", "METHOD", List.of("action_set_quantities_to_reservation"));
        when(registry.getRequired("SET_FULL_QUANTITY")).thenReturn(entry);
        when(rpc.callRpc(anyList())).thenReturn(null);

        assertThrows(MethodResolver.CapabilityProbeException.class,
                () -> resolver.resolve("SET_FULL_QUANTITY"));
    }

    /** The probe must never execute against a real row — an empty recordset is passed. */
    @Test
    void probe_usesEmptyRecordset_soItCanHaveNoSideEffects() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.picking", "METHOD", List.of("action_cancel"));
        when(registry.getRequired("CANCEL_DELIVERY")).thenReturn(entry);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));
        when(rpc.buildArgs(anyString(), anyString(), anyList()))
                .thenAnswer(inv -> List.of(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));

        resolver.resolve("CANCEL_DELIVERY");

        verify(rpc).buildArgs(eq("stock.picking"), eq("action_cancel"), eq(List.of(List.of())));
    }

    /** A version binding takes precedence over the generic candidates for that Odoo major. */
    @Test
    void resolve_prefersVersionBoundNameForDetectedMajor() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.picking", "METHOD", List.of("legacy_name"),
                List.of(new CapabilityRegistry.VersionBinding(19, null, "odoo19_name")));
        when(registry.getRequired("SET_FULL_QUANTITY")).thenReturn(entry);
        when(versionResolver.major()).thenReturn(19);
        when(rpc.callRpc(anyList())).thenReturn(Map.of("result", true));

        assertEquals("odoo19_name", resolver.resolve("SET_FULL_QUANTITY"));
    }
}
