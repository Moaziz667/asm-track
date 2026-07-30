package com.asm.erpadapter.mapping;

import java.util.Map;

/**
 * Where each canonical field is read from on ERPNext when the tenant has mapped nothing.
 *
 * <p>The ERPNext counterpart of {@link OdooDefaultSources}. Every entry was read off the suppliers in
 * {@code ErpNextLookupAdapter} rather than guessed from the doctype, because the point of showing a
 * default is that it is the one actually used.
 *
 * <p>Two entries deserve their comments in the table below: ERPNext has no delivery note for an order
 * still awaiting delivery, so there is no number to read, and the adapter hardcodes priority and
 * readiness rather than reading them from anywhere.
 *
 * <p><b>Keep in step with {@code ErpNextLookupAdapter}.</b> Nothing enforces that today — change the
 * supplier and this table in the same commit, because a default that names the wrong field is worse
 * than one that says nothing.
 */
public final class ErpNextDefaultSources {

    private ErpNextDefaultSources() {}

    private static final Map<CanonicalField, String> BY_FIELD = Map.ofEntries(
            // ── Identity ──────────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.ERP_ORDER_ID, "Sales Order.name"),
            Map.entry(CanonicalField.SALE_ORDER_REF, "Sales Order.name"),
            // No delivery note exists until the order is delivered, so nothing is read for this.
            Map.entry(CanonicalField.BL_NUMBER, FieldMappingResolver.DERIVED),
            Map.entry(CanonicalField.EXTERNAL_REF, "Sales Order.po_no"),

            // ── Recipient ─────────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.CUSTOMER_NAME, "Sales Order.customer_name"),
            Map.entry(CanonicalField.CUSTOMER_PHONE, "Sales Order.contact_mobile"),
            // address_line1 + address_line2 + pincode of the shipping Address, assembled.
            Map.entry(CanonicalField.DELIVERY_ADDRESS, FieldMappingResolver.DERIVED),
            Map.entry(CanonicalField.DELIVERY_CITY, "Address.city"),
            // What the adapter reads — but `instructions` is not a field of Sales Order on ERPNext 16,
            // so this default is always empty and the value only ever arrives once someone maps it.
            // Reported as-is rather than hidden: the hint's job is to say what the code does, and an
            // integrator seeing a name that is not in their picker learns something true.
            Map.entry(CanonicalField.DELIVERY_INSTRUCTIONS, "Sales Order.instructions"),

            // ── Commercial ────────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.TOTAL_AMOUNT, "Sales Order.grand_total"),
            Map.entry(CanonicalField.CURRENCY, "Sales Order.currency"),

            // ── Planning ──────────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.DATE_ORDER, "Sales Order.transaction_date"),
            Map.entry(CanonicalField.SCHEDULED_AT, "Sales Order.delivery_date"),
            // Hardcoded to NORMAL — nothing in ERPNext drives it until someone maps it.
            Map.entry(CanonicalField.PRIORITY, FieldMappingResolver.DERIVED),

            // ── Fulfilment source ─────────────────────────────────────────────────────────────────
            // set_warehouse when the order names one, otherwise the first line's warehouse.
            Map.entry(CanonicalField.WAREHOUSE_CODE, FieldMappingResolver.DERIVED),
            Map.entry(CanonicalField.WAREHOUSE_NAME, FieldMappingResolver.DERIVED),
            // An order in a to-deliver status is ready by definition; nothing is read.
            Map.entry(CanonicalField.READY, FieldMappingResolver.DERIVED),

            // ── Order lines ───────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.ITEM_SKU, "Sales Order Item.item_code"),
            Map.entry(CanonicalField.ITEM_NAME, "Sales Order Item.item_name"),
            // qty minus delivered_qty — what is left to deliver, so a backorder reads correctly.
            Map.entry(CanonicalField.ITEM_QUANTITY, FieldMappingResolver.DERIVED),
            Map.entry(CanonicalField.ITEM_UNIT_PRICE, "Sales Order Item.rate"),
            Map.entry(CanonicalField.ITEM_UNIT_WEIGHT_KG, "Sales Order Item.weight_per_unit"),
            Map.entry(CanonicalField.ITEM_PRODUCT_TYPE, "Sales Order Item.item_group"));

    /** @return the default source path, or {@code DERIVED} when several fields combine into one. */
    public static String of(CanonicalField field) {
        return BY_FIELD.getOrDefault(field, FieldMappingResolver.DERIVED);
    }
}
