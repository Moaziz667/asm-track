package com.asm.erpadapter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Odoo cancellation workflows — action_unlock + action_cancel on sale orders and pickings.
 * Validates that the cancel/unlock methods exist and work across Odoo versions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowCancelIT extends AbstractOdooIntegrationTest {

    @Override
    protected Set<OdooVersion> getTestVersions() {
        return EnumSet.of(OdooVersion.V16, OdooVersion.V19);
    }

    // ── Sale order unlock ────────────────────────────────────────────────────

    @Test
    void odoo16_unlockSaleOrder() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer Unlock V16");
        int productId = data.createProduct("Unlock Product V16", "BAR-UL-001", 40.0);

        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 3, 40.0)));
        data.confirmSaleOrder(soId);

        // Unlock should succeed without error
        data.callMethod("sale.order", "action_unlock", List.of(soId));

        // Verify SO is still in sale state (unlock doesn't cancel)
        Map<String, Object> so = data.read("sale.order", soId, List.of("state"));
        assertEquals("sale", so.get("state"),
                "SO should remain in 'sale' state after unlock");
    }

    @Test
    void odoo19_unlockSaleOrder() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        int partnerId = data.createPartner("Test Customer Unlock V19");
        int productId = data.createProduct("Unlock Product V19", "BAR-UL-019", 40.0);

        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 3, 40.0)));
        data.confirmSaleOrder(soId);

        data.callMethod("sale.order", "action_unlock", List.of(soId));

        Map<String, Object> so = data.read("sale.order", soId, List.of("state"));
        assertEquals("sale", so.get("state"),
                "SO should remain in 'sale' state after unlock");
    }

    // ── Picking cancel ───────────────────────────────────────────────────────

    @Test
    void odoo16_cancelPicking() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer Cancel V16");
        int productId = data.createProduct("Cancel Product V16", "BAR-CN-001", 35.0);

        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 4, 35.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Cancel picking
        data.callMethod("stock.picking", "action_cancel", List.of(pickingId));

        Map<String, Object> cancelledPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("cancel", cancelledPicking.get("state"),
                "Picking should be in 'cancel' state after action_cancel");
    }

    @Test
    void odoo19_cancelPicking() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        int partnerId = data.createPartner("Test Customer Cancel V19");
        int productId = data.createProduct("Cancel Product V19", "BAR-CN-019", 35.0);

        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 4, 35.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        data.callMethod("stock.picking", "action_cancel", List.of(pickingId));

        Map<String, Object> cancelledPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("cancel", cancelledPicking.get("state"),
                "Picking should be in 'cancel' state after action_cancel");
    }

    // ── Unlock + cancel flow ─────────────────────────────────────────────────

    @Test
    void odoo16_unlockThenCancelPicking() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer UnlockCancel V16");
        int productId = data.createProduct("UnlockCancel Product V16", "BAR-UC-001", 50.0);

        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 2, 50.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Set partial qty to lock the picking
        data.setQuantityDone(pickingId, productId, 1);
        data.callMethod("stock.picking", "button_validate", List.of(pickingId));

        // Unlock SO first
        data.callMethod("sale.order", "action_unlock", List.of(soId));

        // Now cancel the picking
        data.callMethod("stock.picking", "action_cancel", List.of(pickingId));

        Map<String, Object> cancelledPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("cancel", cancelledPicking.get("state"),
                "Picking should be 'cancel' after unlock + cancel flow");
    }

    @Test
    void odoo19_unlockThenCancelPicking() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        int partnerId = data.createPartner("Test Customer UnlockCancel V19");
        int productId = data.createProduct("UnlockCancel Product V19", "BAR-UC-019", 50.0);

        int soId = data.createSaleOrder(partnerId, List.of(
                new OdooTestDataFactory.OrderLine(productId, 2, 50.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Set partial qty to lock the picking
        data.setQuantityField(pickingId, productId, 1, "quantity");
        data.callMethod("stock.picking", "button_validate", List.of(pickingId));

        // Unlock SO first
        data.callMethod("sale.order", "action_unlock", List.of(soId));

        // Now cancel the picking
        data.callMethod("stock.picking", "action_cancel", List.of(pickingId));

        Map<String, Object> cancelledPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("cancel", cancelledPicking.get("state"),
                "Picking should be 'cancel' after unlock + cancel flow");
    }
}
