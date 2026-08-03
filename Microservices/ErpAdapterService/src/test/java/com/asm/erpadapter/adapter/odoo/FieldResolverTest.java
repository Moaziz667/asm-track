package com.asm.erpadapter.adapter.odoo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FieldResolverTest {

    @Mock private OdooJsonRpcClient rpc;
    @Mock private CapabilityRegistry registry;
    @Mock private OdooMetadataCache metadataCache;
    @Mock private OdooVersionResolver versionResolver;

    private FieldResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FieldResolver(rpc, registry, metadataCache, versionResolver);
    }

    @Test
    void resolve_returnsFirstAvailableCandidate() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(entry);
        when(metadataCache.getAvailableFields("stock.move.line")).thenReturn(Set.of("qty_done"));

        String result = resolver.resolve("DONE_QUANTITY");

        assertEquals("qty_done", result);
    }

    @Test
    void resolve_returnsFirstWhenMultipleAvailable() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(entry);
        when(metadataCache.getAvailableFields("stock.move.line")).thenReturn(Set.of("quantity", "qty_done"));

        String result = resolver.resolve("DONE_QUANTITY");

        assertEquals("quantity", result);
    }

    @Test
    void resolve_throwsFieldResolutionException_whenNoCandidateFound() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(entry);
        when(metadataCache.getAvailableFields("stock.move.line")).thenReturn(Set.of("unrelated_field"));

        FieldResolver.FieldResolutionException ex = assertThrows(
                FieldResolver.FieldResolutionException.class,
                () -> resolver.resolve("DONE_QUANTITY"));

        assertEquals("DONE_QUANTITY", ex.getCapability());
        assertEquals("stock.move.line", ex.getModel());
        assertEquals(2, ex.getCandidates().size());
    }

    @Test
    void resolve_throwsIllegalArgument_forMethodCapability() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.return.picking", "METHOD", List.of("action_create_returns"));
        when(registry.getRequired("CREATE_RETURN")).thenReturn(entry);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("CREATE_RETURN"));
    }

    @Test
    void resolveWithOverride_usesOverride_whenFieldExists() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(entry);
        when(metadataCache.getAvailableFields("stock.move.line")).thenReturn(Set.of("x_custom_qty", "qty_done"));

        String result = resolver.resolveWithOverride("DONE_QUANTITY", "x_custom_qty");

        assertEquals("x_custom_qty", result);
    }

    @Test
    void resolveWithOverride_fallsBackToDefault_whenOverrideNotExists() {
        CapabilityRegistry.CapabilityEntry entry = new CapabilityRegistry.CapabilityEntry(
                "stock.move.line", "FIELD", List.of("quantity", "qty_done"));
        when(registry.getRequired("DONE_QUANTITY")).thenReturn(entry);
        when(metadataCache.getAvailableFields("stock.move.line")).thenReturn(Set.of("qty_done"));

        String result = resolver.resolveWithOverride("DONE_QUANTITY", "x_nonexistent");

        assertEquals("qty_done", result);
    }

    @Test
    void fieldExists_delegatesToMetadataCache() {
        when(metadataCache.getAvailableFields("stock.move.line")).thenReturn(Set.of("qty_done"));

        assertTrue(resolver.fieldExists("stock.move.line", "qty_done"));
        assertFalse(resolver.fieldExists("stock.move.line", "quantity"));
    }
}
