package com.asm.erpadapter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Odoo delivery validation workflows — full delivery, partial delivery, backorder creation.
 * Validates that the Odoo RPC operations our code relies on actually work across versions.
 *
 * <p>These integration tests call Odoo methods directly to validate the Odoo contract.
 * They do NOT test the Capability Engine — that's the unit tests' job.
 *
 * <p>Odoo 16: qty_done field, create_returns method
 * <p>Odoo 19: quantity field, action_create_returns method
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowValidationIT extends AbstractOdooIntegrationTest {

    @Override
    protected Set<OdooVersion> getTestVersions() {
        return EnumSet.of(OdooVersion.V16, OdooVersion.V19);
    }

    // ── Full delivery ────────────────────────────────────────────────────────

    @Test
    void odoo16_fullDelivery_validate() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer FullDel V16");
        int productId = data.createProduct("FullDel Product V16", "BAR-FD-001", 25.0);

        int soId = data.createSaleOrder(partnerId, List.of(new OdooTestDataFactory.OrderLine(productId, 10, 25.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist after confirming SO");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Set qty_done = 10 (full quantity)
        data.setQuantityDone(pickingId, productId, 10);

        // Validate picking — should succeed without backorder
        Object result = data.callMethod("stock.picking", "button_validate", List.of(pickingId));
        assertNotNull(result, "button_validate should return a result");

        // Verify picking state is done
        Map<String, Object> updatedPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("done", updatedPicking.get("state"),
                "Picking should be in 'done' state after full validation");
    }

    @Test
    void odoo19_fullDelivery_validate() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        int partnerId = data.createPartner("Test Customer FullDel V19");
        int productId = data.createProduct("FullDel Product V19", "BAR-FD-019", 25.0);

        int soId = data.createSaleOrder(partnerId, List.of(new OdooTestDataFactory.OrderLine(productId, 10, 25.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist after confirming SO");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Set quantity = 10 (full quantity, Odoo 19 field name)
        data.setQuantityField(pickingId, productId, 10, "quantity");

        // Validate picking — should succeed without backorder
        Object result = data.callMethod("stock.picking", "button_validate", List.of(pickingId));
        assertNotNull(result, "button_validate should return a result");

        // Verify picking state is done
        Map<String, Object> updatedPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("done", updatedPicking.get("state"),
                "Picking should be in 'done' state after full validation");
    }

    // ── Partial delivery → backorder ─────────────────────────────────────────

    @Test
    void odoo16_partialDelivery_createsBackorder() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer Partial V16");
        int productId = data.createProduct("Partial Product V16", "BAR-PD-001", 25.0);

        int soId = data.createSaleOrder(partnerId, List.of(new OdooTestDataFactory.OrderLine(productId, 10, 25.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Set qty_done = 3 (partial delivery)
        data.setQuantityDone(pickingId, productId, 3);

        // Validate picking — should return a backorder confirmation wizard
        Object result = data.callMethod("stock.picking", "button_validate", List.of(pickingId));
        assertNotNull(result, "button_validate should return backorder wizard");
        assertEquals("stock.backorder.confirmation", asAction(result).get("res_model"),
                "A partial delivery should ask to create a backorder");

        // Answer it. The action carries no res_id — only defaults — so the wizard has to be built
        // before it can be processed; see settleValidationWizards.
        data.settleValidationWizards(result);

        // Verify original picking is done
        Map<String, Object> updatedPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("done", updatedPicking.get("state"),
                "Original picking should be 'done' after backorder creation");

        // Verify a backorder picking was created.
        //
        // Matched on backorder_id, the way OdooPickingService.findBackorderPickingId does it. The
        // obvious-looking `origin = <this picking's name>` finds nothing: `origin` carries the sale
        // order that started the chain, not the transfer being split, so it is identical on the
        // original and its backorder and identifies neither.
        List<Map<String, Object>> backorderPickings = data.searchRead("stock.picking",
                List.of(List.of("backorder_id", "=", pickingId)),
                List.of("id", "state", "backorder_id"), 10);
        assertFalse(backorderPickings.isEmpty(),
                "A backorder picking should have been created for picking " + pickingId);
    }

    @Test
    void odoo19_partialDelivery_createsBackorder() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        int partnerId = data.createPartner("Test Customer Partial V19");
        int productId = data.createProduct("Partial Product V19", "BAR-PD-019", 25.0);

        int soId = data.createSaleOrder(partnerId, List.of(new OdooTestDataFactory.OrderLine(productId, 10, 25.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Set quantity = 3 (partial delivery, Odoo 19 field name)
        data.setQuantityField(pickingId, productId, 3, "quantity");

        // Validate picking — should return a backorder confirmation wizard
        Object result = data.callMethod("stock.picking", "button_validate", List.of(pickingId));
        assertNotNull(result, "button_validate should return backorder wizard");
        assertEquals("stock.backorder.confirmation", asAction(result).get("res_model"),
                "A partial delivery should ask to create a backorder");

        data.settleValidationWizards(result);

        // Verify original picking is done
        Map<String, Object> updatedPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("done", updatedPicking.get("state"),
                "Original picking should be 'done' after backorder creation");

        // Verify a backorder picking was created.
        //
        // Matched on backorder_id, the way OdooPickingService.findBackorderPickingId does it. The
        // obvious-looking `origin = <this picking's name>` finds nothing: `origin` carries the sale
        // order that started the chain, not the transfer being split, so it is identical on the
        // original and its backorder and identifies neither.
        List<Map<String, Object>> backorderPickings = data.searchRead("stock.picking",
                List.of(List.of("backorder_id", "=", pickingId)),
                List.of("id", "state", "backorder_id"), 10);
        assertFalse(backorderPickings.isEmpty(),
                "A backorder picking should have been created for picking " + pickingId);
    }

    // ── Immediate transfer ───────────────────────────────────────────────────
    //
    // Validating with no quantity set is where the two versions genuinely part ways, and the split
    // is worth pinning: Odoo 16 asks "Immediate Transfer?" and then, once answered, asks "Create
    // Backorder?" on top — two wizards for one click. Odoo 19 asks nothing and returns `true`.
    // Either way the picking must end up done, which is all the connector cares about.

    @Test
    void odoo16_immediateTransfer_whenNoQtySet() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer Immediate V16");
        int productId = data.createProduct("Immediate Product V16", "BAR-IT-001", 25.0);

        int soId = data.createSaleOrder(partnerId, List.of(new OdooTestDataFactory.OrderLine(productId, 5, 25.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Don't set any qty — Odoo 16 asks to confirm an immediate transfer, and then asks again
        // about the backorder. settleValidationWizards answers whatever it is asked, in order.
        Object result = data.callMethod("stock.picking", "button_validate", List.of(pickingId));
        data.settleValidationWizards(result);

        Map<String, Object> updatedPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("done", updatedPicking.get("state"),
                "Picking should be 'done' after immediate transfer");
    }

    @Test
    void odoo19_immediateTransfer_whenNoQtySet() {
        OdooTestDataFactory data = getFactory(OdooVersion.V19);
        int partnerId = data.createPartner("Test Customer Immediate V19");
        int productId = data.createProduct("Immediate Product V19", "BAR-IT-019", 25.0);

        int soId = data.createSaleOrder(partnerId, List.of(new OdooTestDataFactory.OrderLine(productId, 5, 25.0)));
        data.confirmSaleOrder(soId);

        Map<String, Object> picking = data.findPickingBySaleOrder(soId);
        assertNotNull(picking, "Picking should exist");
        int pickingId = ((Number) picking.get("id")).intValue();

        // Don't set any qty — validate should trigger immediate transfer wizard
        Object result = data.callMethod("stock.picking", "button_validate", List.of(pickingId));

        if (result instanceof Map<?, ?> wizard) {
            String wizardModel = String.valueOf(wizard.get("res_model"));
            if ("stock.immediate.transfer".equals(wizardModel)) {
                Object wizardId = wizard.get("res_id");
                if (wizardId instanceof Number n && n.intValue() > 0) {
                    data.callMethod("stock.immediate.transfer", "process", List.of(n.intValue()));
                }
            }
        }

        Map<String, Object> updatedPicking = data.read("stock.picking", pickingId, List.of("state"));
        assertEquals("done", updatedPicking.get("state"),
                "Picking should be 'done' after immediate transfer");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Read a {@code button_validate} answer as the window action it is.
     *
     * <p>Fails loudly when it is not one. Odoo returns {@code true} for a validation that needed no
     * confirmation, and a test that quietly accepted that would report success for a backorder it
     * never created.
     */
    private static Map<?, ?> asAction(Object validateResult) {
        assertInstanceOf(Map.class, validateResult,
                "Expected a confirmation wizard action, got: " + validateResult);
        return (Map<?, ?>) validateResult;
    }
}
