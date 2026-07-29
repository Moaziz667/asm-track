package com.asm.erpadapter.mapping;

import java.util.Map;

/**
 * Where each canonical field is read from when the tenant has mapped nothing.
 *
 * <p>Purely descriptive: the actual defaults are the suppliers passed to {@code mappedString(...)} and
 * friends in {@code OdooLookupAdapter}. This exists so the mapping screen can say
 * "défaut · res.partner.phone" instead of just "défaut", which is the difference between an
 * integrator knowing what they are about to override and guessing at it.
 *
 * <p>Lives beside the Odoo adapter rather than on {@link CanonicalField}, which is deliberately
 * provider-neutral — {@code partner_id.phone} is Odoo's answer, ERPNext's is {@code customer.mobile_no},
 * and putting either in the shared vocabulary would start the drift the enum's javadoc warns about.
 *
 * <p>A few defaults are not a single field, and saying so plainly beats naming one of the candidates
 * and being wrong two thirds of the time.
 *
 * <p><b>Keep in step with {@code OdooLookupAdapter}.</b> A description that drifts from the supplier
 * beside it is worse than none: it would confidently point an integrator at the wrong field. Nothing
 * enforces that today — the two are held together by whoever edits them, so change the supplier and
 * this table in the same commit.
 */
public final class OdooDefaultSources {

    private OdooDefaultSources() {}

    /** Marks a default no single path can express, so the UI can render it as prose. */
    public static final String DERIVED = "—";

    private static final Map<CanonicalField, String> BY_FIELD = Map.ofEntries(
            // ── Identity ──────────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.ERP_ORDER_ID, "stock.picking.name"),
            Map.entry(CanonicalField.BL_NUMBER, "stock.picking.name"),
            // sale_id's label, falling back to the picking's origin text.
            Map.entry(CanonicalField.SALE_ORDER_REF, DERIVED),
            Map.entry(CanonicalField.EXTERNAL_REF, "sale.order.client_order_ref"),

            // ── Recipient ─────────────────────────────────────────────────────────────────────────
            // Tries the sale order's partner, then the picking's, then the picking label.
            Map.entry(CanonicalField.CUSTOMER_NAME, DERIVED),
            Map.entry(CanonicalField.CUSTOMER_PHONE, "res.partner.phone"),
            // street + street2 + city + zip + country, assembled.
            Map.entry(CanonicalField.DELIVERY_ADDRESS, DERIVED),
            Map.entry(CanonicalField.DELIVERY_CITY, "res.partner.city"),
            Map.entry(CanonicalField.DELIVERY_INSTRUCTIONS, "sale.order.note"),

            // ── Commercial ────────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.TOTAL_AMOUNT, "sale.order.amount_total"),
            Map.entry(CanonicalField.CURRENCY, DERIVED),
            Map.entry(CanonicalField.PAYMENT_TERM_NAME, "sale.order.payment_term_id"),

            // ── Planning ──────────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.DATE_ORDER, "sale.order.date_order"),
            Map.entry(CanonicalField.SCHEDULED_AT, "stock.picking.scheduled_date"),
            // Hardcoded to NORMAL — nothing in Odoo drives it until someone maps it.
            Map.entry(CanonicalField.PRIORITY, DERIVED),

            // ── Fulfilment source ─────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.WAREHOUSE_CODE, "stock.warehouse.code"),
            Map.entry(CanonicalField.WAREHOUSE_NAME, "stock.warehouse.name"),
            // state == "assigned".
            Map.entry(CanonicalField.READY, DERIVED),

            // ── Order lines ───────────────────────────────────────────────────────────────────────
            Map.entry(CanonicalField.ITEM_SKU, "product.product.default_code"),
            Map.entry(CanonicalField.ITEM_NAME, "stock.move.product_id"),
            Map.entry(CanonicalField.ITEM_QUANTITY, "stock.move.product_uom_qty"),
            Map.entry(CanonicalField.ITEM_UNIT_PRICE, "sale.order.line.price_unit"),
            Map.entry(CanonicalField.ITEM_UNIT_WEIGHT_KG, "product.product.weight"),
            Map.entry(CanonicalField.ITEM_PRODUCT_TYPE, "product.product.type"));

    /** @return the default source path, or {@link #DERIVED} when several fields combine into one. */
    public static String of(CanonicalField field) {
        return BY_FIELD.getOrDefault(field, DERIVED);
    }
}
