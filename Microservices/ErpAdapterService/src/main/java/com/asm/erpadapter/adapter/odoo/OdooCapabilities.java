package com.asm.erpadapter.adapter.odoo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source of truth for the Odoo dialect differences ASM depends on, resolved per tenant from the
 * detected version ({@link OdooVersionResolver}). The adapter and the conformance probe both read from
 * here, so "what field/method does this tenant's Odoo use" is decided in exactly one place instead of
 * being hardcoded (and silently wrong on the wrong version).
 *
 * <p>Boundaries are documented from Odoo's own history. Where a boundary is fuzzy across a version
 * (method renames), prefer {@link #createReturnsMethodCandidates()} + a call-with-fallback at the call
 * site over a hard guess.
 */
@Component
@RequiredArgsConstructor
public class OdooCapabilities {

    private final OdooVersionResolver version;

    /**
     * The done-quantity field on {@code stock.move.line}. Odoo 17 renamed {@code qty_done} → {@code
     * quantity}; on ≤16 writing {@code quantity} sets the RESERVED qty (a silent stock bug), so this must
     * be version-correct.
     */
    public String doneQtyField() {
        return version.major() >= 17 ? "quantity" : "qty_done";
    }

    /**
     * Method on {@code stock.return.picking} that builds the reverse pickings. Renamed
     * {@code create_returns} → {@code action_create_returns} (v18). Use the candidate list for a resilient
     * call-with-fallback rather than trusting the boundary blindly.
     */
    public String createReturnsMethod() {
        return version.major() >= 18 ? "action_create_returns" : "create_returns";
    }

    /** Preferred method first, alternate second — for a call-with-fallback that survives a fuzzy boundary. */
    public List<String> createReturnsMethodCandidates() {
        return version.major() >= 18
                ? List.of("action_create_returns", "create_returns")
                : List.of("create_returns", "action_create_returns");
    }
}
