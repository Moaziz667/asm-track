package com.asm.erpadapter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.EnumSet;
import java.util.Set;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the Testcontainers infrastructure works:
 * - Odoo containers start and are reachable
 * - Authentication succeeds
 * - Basic JSON-RPC calls work
 * - fields_get returns expected fields
 *
 * <p>This is the first integration test to run — if it fails, nothing else will work.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OdooInfrastructureIT extends AbstractOdooIntegrationTest {

    @Override
    protected Set<OdooVersion> getTestVersions() {
        return EnumSet.of(OdooVersion.V16, OdooVersion.V17, OdooVersion.V18);
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @Test
    void odoo16_authenticate() {
        int uid = getOdoo(OdooVersion.V16).authenticate();
        assertTrue(uid > 0, "Odoo 16 authentication should return positive uid, got " + uid);
    }

    @Test
    void odoo17_authenticate() {
        int uid = getOdoo(OdooVersion.V17).authenticate();
        assertTrue(uid > 0, "Odoo 17 authentication should return positive uid, got " + uid);
    }

    @Test
    void odoo18_authenticate() {
        int uid = getOdoo(OdooVersion.V18).authenticate();
        assertTrue(uid > 0, "Odoo 18 authentication should return positive uid, got " + uid);
    }

    // ── RPC health check ─────────────────────────────────────────────────────

    @Test
    void odoo16_searchRead_products() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        List<Map<String, Object>> products = data.searchRead("product.product",
                List.of(), List.of("id", "name"), 5);
        assertNotNull(products, "product.product search_read should return a list");
        // Odoo comes with demo products — we expect at least 0 (fresh DB may have none)
    }

    @Test
    void odoo17_searchRead_products() {
        OdooTestDataFactory data = getFactory(OdooVersion.V17);
        List<Map<String, Object>> products = data.searchRead("product.product",
                List.of(), List.of("id", "name"), 5);
        assertNotNull(products, "product.product search_read should return a list");
    }

    // ── fields_get verification ──────────────────────────────────────────────

    @Test
    void odoo16_fieldsGet_stockMoveLine_hasQtyDone() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        java.util.Set<String> fields = data.getFieldNames("stock.move.line");
        assertTrue(fields.contains("qty_done"),
                "Odoo 16 stock.move.line should have 'qty_done'. Fields: " + fields);
        assertFalse(fields.contains("quantity"),
                "Odoo 16 stock.move.line should NOT have 'quantity'. Fields: " + fields);
    }

    @Test
    void odoo17_fieldsGet_stockMoveLine_hasQuantity() {
        OdooTestDataFactory data = getFactory(OdooVersion.V17);
        java.util.Set<String> fields = data.getFieldNames("stock.move.line");
        assertTrue(fields.contains("quantity"),
                "Odoo 17 stock.move.line should have 'quantity'. Fields: " + fields);
    }

    @Test
    void odoo18_fieldsGet_stockMoveLine_hasQuantity() {
        OdooTestDataFactory data = getFactory(OdooVersion.V18);
        java.util.Set<String> fields = data.getFieldNames("stock.move.line");
        assertTrue(fields.contains("quantity"),
                "Odoo 18 stock.move.line should have 'quantity'. Fields: " + fields);
    }

    // ── Method existence check ───────────────────────────────────────────────

    @Test
    void odoo16_methodExists_action_unlock_on_sale_order() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        // Try calling action_unlock on a non-existent record — should get "no record" error, not "method not found"
        try {
            data.callMethod("sale.order", "action_unlock", List.of(999999));
        } catch (Exception e) {
            // Expected — method exists but record doesn't
            assertFalse(e.getMessage().contains("does not exist"),
                    "action_unlock should exist on sale.order in Odoo 16");
        }
    }

    @Test
    void odoo16_methodExists_create_returns_on_stock_return_picking() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        try {
            data.callMethod("stock.return.picking", "create_returns", List.of(999999));
        } catch (Exception e) {
            assertFalse(e.getMessage().contains("does not exist"),
                    "create_returns should exist on stock.return.picking in Odoo 16");
        }
    }

    @Test
    void odoo18_methodExists_action_create_returns_on_stock_return_picking() {
        OdooTestDataFactory data = getFactory(OdooVersion.V18);
        try {
            data.callMethod("stock.return.picking", "action_create_returns", List.of(999999));
        } catch (Exception e) {
            assertFalse(e.getMessage().contains("does not exist"),
                    "action_create_returns should exist on stock.return.picking in Odoo 18");
        }
    }

    // ── Basic data creation ──────────────────────────────────────────────────

    @Test
    void odoo16_createProduct_andReadBack() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int productId = data.createProduct("Test Product Infra", "BAR-INFRA-001", 99.99);
        assertTrue(productId > 0, "Product creation should return positive ID");

        Map<String, Object> read = data.read("product.product", productId, List.of("name", "barcode"));
        assertEquals("Test Product Infra", read.get("name"));
        assertEquals("BAR-INFRA-001", read.get("barcode"));
    }

    @Test
    void odoo16_createPartner_andReadBack() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        int partnerId = data.createPartner("Test Customer Infra");
        assertTrue(partnerId > 0, "Partner creation should return positive ID");

        Map<String, Object> read = data.read("res.partner", partnerId, List.of("name"));
        assertEquals("Test Customer Infra", read.get("name"));
    }
}
