package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.entity.ErpMapping;
import com.asm.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CapabilityResolverTest {

    @Mock private CapabilityCache cache;
    @Mock private CapabilityRegistry registry;
    @Mock private FieldResolver fieldResolver;
    @Mock private MethodResolver methodResolver;
    @Mock private ErpMappingService mappingService;

    private CapabilityResolver resolver;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new CapabilityResolver(cache, registry, fieldResolver, methodResolver, mappingService);
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void resolve_returnsCachedValue_whenPresent() {
        when(cache.get("DONE_QUANTITY")).thenReturn("qty_done");

        String result = resolver.resolve("DONE_QUANTITY");

        assertEquals("qty_done", result);
        verifyNoInteractions(mappingService, fieldResolver, methodResolver);
    }

    @Test
    void resolve_queriesMapping_whenNoCache() {
        when(cache.get("DONE_QUANTITY")).thenReturn(null);
        when(mappingService.findByTenantAndCapability(tenantId, "DONE_QUANTITY"))
                .thenReturn(Optional.empty());
        CapabilityRegistry.CapabilityEntry fieldEntry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", java.util.List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(fieldEntry);
        when(fieldResolver.resolve("DONE_QUANTITY")).thenReturn("qty_done");

        String result = resolver.resolve("DONE_QUANTITY");

        assertEquals("qty_done", result);
        verify(cache).put("DONE_QUANTITY", "qty_done");
    }

    @Test
    void resolve_usesClientOverride_whenMappingExists() {
        when(cache.get("DONE_QUANTITY")).thenReturn(null);
        ErpMapping mapping = mock(ErpMapping.class);
        when(mapping.getOdooName()).thenReturn("x_custom_qty");
        when(mappingService.findByTenantAndCapability(tenantId, "DONE_QUANTITY"))
                .thenReturn(Optional.of(mapping));
        CapabilityRegistry.CapabilityEntry fieldEntry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", java.util.List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(fieldEntry);
        when(fieldResolver.resolveWithOverride("DONE_QUANTITY", "x_custom_qty")).thenReturn("x_custom_qty");

        String result = resolver.resolve("DONE_QUANTITY");

        assertEquals("x_custom_qty", result);
        verify(cache).put("DONE_QUANTITY", "x_custom_qty");
    }

    @Test
    void resolve_usesMethodResolver_forMethodCapability() {
        when(cache.get("CREATE_RETURN")).thenReturn(null);
        when(mappingService.findByTenantAndCapability(tenantId, "CREATE_RETURN"))
                .thenReturn(Optional.empty());
        CapabilityRegistry.CapabilityEntry methodEntry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", java.util.List.of("action_create_returns", "create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(methodEntry);
        when(methodResolver.resolve("CREATE_RETURN")).thenReturn("action_create_returns");

        String result = resolver.resolve("CREATE_RETURN");

        assertEquals("action_create_returns", result);
        verify(cache).put("CREATE_RETURN", "action_create_returns");
    }

    @Test
    void resolveWithOverride_delegatesToMethodResolver() {
        when(registry.getRequired("CREATE_RETURN")).thenReturn(new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", java.util.List.of("action_create_returns")));
        when(methodResolver.resolveWithOverride("CREATE_RETURN", "custom_method")).thenReturn("custom_method");

        String result = resolver.resolveWithOverride("CREATE_RETURN", "custom_method");

        assertEquals("custom_method", result);
        verify(cache).put("CREATE_RETURN", "custom_method");
    }

    @Test
    void resolveWithOverride_fallsBackToDefault_whenOverrideBlank() {
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", java.util.List.of("qty_done")));
        when(cache.get("DONE_QUANTITY")).thenReturn(null);
        when(mappingService.findByTenantAndCapability(tenantId, "DONE_QUANTITY"))
                .thenReturn(Optional.empty());
        when(fieldResolver.resolve("DONE_QUANTITY")).thenReturn("qty_done");

        String result = resolver.resolveWithOverride("DONE_QUANTITY", "  ");

        assertEquals("qty_done", result);
    }

    @Test
    void supports_delegatesToRegistry() {
        when(registry.contains("DONE_QUANTITY")).thenReturn(true);

        assertTrue(resolver.supports(CanonicalCapability.DONE_QUANTITY));
        verify(registry).contains("DONE_QUANTITY");
    }

    @Test
    void supports_returnsFalse_whenNotRegistered() {
        when(registry.contains("CANCEL_DELIVERY")).thenReturn(false);

        assertFalse(resolver.supports(CanonicalCapability.CANCEL_DELIVERY));
        verify(registry).contains("CANCEL_DELIVERY");
    }

    @Test
    void getCandidates_delegatesToRegistry() {
        when(registry.getCandidates("CREATE_RETURN"))
                .thenReturn(java.util.List.of("action_create_returns", "create_returns"));

        java.util.List<String> candidates = resolver.getCandidates(CanonicalCapability.CREATE_RETURN);

        assertEquals(2, candidates.size());
        assertEquals("action_create_returns", candidates.get(0));
    }
}
