package com.asm.erpadapter.adapter.odoo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Odoo fills the return wizard's lines in {@code default_get}, which the web client triggers but a
 * plain RPC {@code create()} does not — on Odoo 16 the wizard came back empty and
 * {@code create_returns} refused with "specify at least one non-zero quantity", so every RMA against
 * a v16 tenant dead-lettered. Odoo 17+ computes the lines, which is why only 16 broke.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OdooReturnWizardLinesTest {

    @Mock private OdooJsonRpcClient rpc;
    @Mock private CapabilityResolver capabilityResolver;

    private OdooProductService service;

    @BeforeEach
    void setUp() {
        service = new OdooProductService(rpc, capabilityResolver);
    }

    @Test
    void materialisesOneLinePerSourceMoveWhenOdooLeftTheWizardEmpty() {
        when(rpc.callRpc(any()))
                // search existing wizard lines -> none (Odoo 16 behaviour)
                .thenReturn(Map.of("result", List.of()))
                // search_read source picking moves
                .thenReturn(Map.of("result", List.of(
                        Map.of("id", 11, "product_id", List.of(5, "[ASM-T3] Widget")),
                        Map.of("id", 12, "product_id", List.of(6, "[ASM-T4] Gadget")))))
                // two line creates
                .thenReturn(Map.of("result", 101))
                .thenReturn(Map.of("result", 102));

        assertThat(service.ensureReturnWizardLines(7, 99)).isEqualTo(2);

        verify(rpc).buildArgs(eq("stock.return.picking.line"), eq("create"),
                argThat(a -> a.get(0).toString().contains("move_id=11")));
        verify(rpc).buildArgs(eq("stock.return.picking.line"), eq("create"),
                argThat(a -> a.get(0).toString().contains("move_id=12")));
    }

    /** Odoo 17+ already computed the lines — leave them alone rather than duplicating them. */
    @Test
    void leavesExistingLinesUntouched() {
        when(rpc.callRpc(any())).thenReturn(Map.of("result", List.of(201, 202)));

        assertThat(service.ensureReturnWizardLines(7, 99)).isEqualTo(2);

        verify(rpc, never()).buildArgs(eq("stock.return.picking.line"), eq("create"), any());
    }

    /** No moves on the source picking means there is genuinely nothing to return. */
    @Test
    void returnsZeroWhenSourcePickingHasNoMoves() {
        when(rpc.callRpc(any()))
                .thenReturn(Map.of("result", List.of()))
                .thenReturn(Map.of("result", List.of()));

        assertThat(service.ensureReturnWizardLines(7, 99)).isZero();
    }
}
