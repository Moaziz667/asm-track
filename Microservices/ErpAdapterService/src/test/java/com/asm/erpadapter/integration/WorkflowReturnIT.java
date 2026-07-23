package com.asm.erpadapter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Odoo return picking workflows — create_returns / action_create_returns.
 * Validates that the return method exists and works for each Odoo version.
 *
 * <p>Odoo 16-17: create_returns on stock.return.picking
 * <p>Odoo 18+: action_create_returns on stock.return.picking
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowReturnIT extends AbstractOdooIntegrationTest {

    @Override
    protected Set<OdooVersion> getTestVersions() {
        return EnumSet.of(OdooVersion.V16, OdooVersion.V19);
    }

    // ── Return creation ──────────────────────────────────────────────────────

    @Test
    void odoo16_returnPicking_createReturns() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer Return V16");
        int productId = data.createProduct("Return Product V16", "BAR-RT-001", 30.0);

        // Create and confirm SO
        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 5, 30.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Validate picking (full delivery)
        data.setQuantityDone(pickingId, productId, 5);
        data.callMethod("stock.picking", "button_validate", List.of(pickingId));

        // Create return wizard
        Object returnWizard = data.callMethod("stock.return.picking", "create", List.of(
                Map.of("picking_id", pickingId)));
        assertNotNull(returnWizard, "Return wizard should be created");

        int returnWizardId;
        if (returnWizard instanceof Number n) {
            returnWizardId = n.intValue();
        } else if (returnWizard instanceof Map<?, ?> m) {
            returnWizardId = ((Number) m.get("res_id")).intValue();
        } else {
            fail("Unexpected return wizard type: " + returnWizard.getClass());
            return;
        }

        // Execute return — Odoo 16 uses create_returns
        Object returnResult = data.callMethod("stock.return.picking", "create_returns",
                List.of(returnWizardId));
        assertNotNull(returnResult, "create_returns should return a result (picking ID or dict)");

        // Verify a return picking was created
        int returnPickingId;
        if (returnResult instanceof Number n) {
            returnPickingId = n.intValue();
        } else if (returnResult instanceof Map<?, ?> m) {
            Object rid = m.get("res_id");
            if (rid == null) rid = m.get("id");
            returnPickingId = ((Number) rid).intValue();
        } else {
            returnPickingId = -1;
        }
        assertTrue(returnPickingId > 0, "Return picking ID should be positive, got " + returnPickingId);

        Map<String, Object> returnPicking = data.read("stock.picking", returnPickingId,
                List.of("state", "picking_type_id"));
        assertNotNull(returnPicking.get("state"), "Return picking should have a state");
    }

    @Test
    void odoo19_returnPicking_actionCreateReturns() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        int partnerId = data.createPartner("Test Customer Return V19");
        int productId = data.createProduct("Return Product V19", "BAR-RT-019", 30.0);

        // Create and confirm SO
        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 5, 30.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Validate picking (full delivery)
        data.setQuantityField(pickingId, productId, 5, "quantity");
        data.callMethod("stock.picking", "button_validate", List.of(pickingId));

        // Create return wizard
        Object returnWizard = data.callMethod("stock.return.picking", "create", List.of(
                Map.of("picking_id", pickingId)));
        assertNotNull(returnWizard, "Return wizard should be created");

        int returnWizardId;
        if (returnWizard instanceof Number n) {
            returnWizardId = n.intValue();
        } else if (returnWizard instanceof Map<?, ?> m) {
            returnWizardId = ((Number) m.get("res_id")).intValue();
        } else {
            fail("Unexpected return wizard type: " + returnWizard.getClass());
            return;
        }

        // Execute return — Odoo 19 uses action_create_returns
        Object returnResult = data.callMethod("stock.return.picking", "action_create_returns",
                List.of(returnWizardId));
        assertNotNull(returnResult, "action_create_returns should return a result");

        int returnPickingId;
        if (returnResult instanceof Number n) {
            returnPickingId = n.intValue();
        } else if (returnResult instanceof Map<?, ?> m) {
            Object rid = m.get("res_id");
            if (rid == null) rid = m.get("id");
            returnPickingId = ((Number) rid).intValue();
        } else {
            returnPickingId = -1;
        }
        assertTrue(returnPickingId > 0, "Return picking ID should be positive, got " + returnPickingId);

        Map<String, Object> returnPicking = data.read("stock.picking", returnPickingId,
                List.of("state", "picking_type_id"));
        assertNotNull(returnPicking.get("state"), "Return picking should have a state");
    }

    // ── Unknown wizard fail-fast ─────────────────────────────────────────────

    @Test
    void odoo16_unknownWizard_returnsError() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        // Try to call a non-existent wizard method — should throw
        assertThrows(Exception.class, () ->
                data.callMethod("stock.non.existent.wizard", "non_existent_method", List.of(999999)),
                "Calling a non-existent wizard should throw an exception");
    }

    @Test
    void odoo19_unknownWizard_returnsError() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        assertThrows(Exception.class, () ->
                data.callMethod("stock.non.existent.wizard", "non_existent_method", List.of(999999)),
                "Calling a non-existent wizard should throw an exception");
    }
}
