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
        // Only V16 in CI for now (dind not available). V17/V18 need separate containers.
        return EnumSet.of(OdooVersion.V16);
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @Test
    void odoo16_authenticate() {
        int uid = getOdoo(OdooVersion.V16).authenticate();
        assertTrue(uid > 0, "Odoo 16 authentication should return positive uid, got " + uid);
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

    // ── Method existence check ───────────────────────────────────────────────

    @Test
    void odoo16_methodExists_action_unlock_on_sale_order() {
        OdooTestDataFactory data = getFactory(OdooVersion.V16);
        try {
            data.callMethod("sale.order", "action_unlock", List.of(999999));
            fail("Expected an exception for non-existent record");
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
            fail("Expected an exception for non-existent record");
        } catch (Exception e) {
            assertFalse(e.getMessage().contains("does not exist"),
                    "create_returns should exist on stock.return.picking in Odoo 16");
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
