package com.asm.erpadapter.adapter.odoo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a driver is told to collect for a delivery note.
 *
 * <p>This used to be the sale order's {@code amount_total}, which is the whole order: on a partial
 * delivery it asked for goods still sitting at the depot, and asked for them a second time when the
 * backorder went out. It is now computed from the note's own moves.
 *
 * <p>The obvious replacement — summing {@code price_unit} — is the trap these tests exist to pin
 * down: that field is untaxed, so a driver would come back short by the VAT and nobody would notice
 * until the accounts were reconciled.
 */
class OdooCollectableAmountTest {

    /** One Odoo stock move, as {@code search_read} returns it. */
    private static Map<String, Object> move(int productId, double qty) {
        return Map.of("product_id", List.of(productId, "Product " + productId),
                      "product_uom_qty", qty);
    }

    @Test
    @DisplayName("a full delivery collects the order's taxed total, unchanged from before")
    void fullDeliveryMatchesOrderTotal() {
        // Order: 4 × 750 + 12 × 32.83, taxed unit prices — total 3 625.80, as Odoo reports it.
        Map<Integer, BigDecimal> taxedUnit = Map.of(
                20, new BigDecimal("800.000"),
                30, new BigDecimal("39.400"));

        BigDecimal amount = OdooLookupAdapter.taxedValueOf(
                List.of(move(20, 4), move(30, 12)), taxedUnit);

        assertThat(amount).isEqualByComparingTo("3672.800");
    }

    @Test
    @DisplayName("half the quantities collect half the money")
    void partialDeliveryCollectsItsShare() {
        Map<Integer, BigDecimal> taxedUnit = Map.of(
                20, new BigDecimal("800.000"),
                30, new BigDecimal("39.400"));

        BigDecimal full = OdooLookupAdapter.taxedValueOf(List.of(move(20, 4), move(30, 12)), taxedUnit);
        BigDecimal half = OdooLookupAdapter.taxedValueOf(List.of(move(20, 2), move(30, 6)), taxedUnit);

        assertThat(half).isEqualByComparingTo(full.divide(new BigDecimal("2")));
    }

    @Test
    @DisplayName("taxes are included — the untaxed subtotal would send a driver back short")
    void amountIsTaxInclusive() {
        // price_unit 750, price_total/qty 800: a 6.67% tax. Summing price_unit loses it.
        Map<Integer, BigDecimal> taxedUnit = Map.of(20, new BigDecimal("800.000"));

        BigDecimal amount = OdooLookupAdapter.taxedValueOf(List.of(move(20, 4)), taxedUnit);

        assertThat(amount).isEqualByComparingTo("3200.000");
        assertThat(amount).isGreaterThan(new BigDecimal("750").multiply(new BigDecimal("4")));
    }

    @Test
    @DisplayName("a line that cannot be priced yields no amount at all, never a partial one")
    void unpricedLineYieldsNothing() {
        // Only product 20 is priced; the note also ships 30. Summing what is known would hand back a
        // figure that looks like a total and is not — the failure this must not have.
        Map<Integer, BigDecimal> taxedUnit = Map.of(20, new BigDecimal("800.000"));

        BigDecimal amount = OdooLookupAdapter.taxedValueOf(
                List.of(move(20, 4), move(30, 12)), taxedUnit);

        assertThat(amount).isNull();
    }

    @Test
    @DisplayName("no priced line at all yields no amount")
    void noPricesYieldNothing() {
        assertThat(OdooLookupAdapter.taxedValueOf(List.of(move(20, 4)), Map.of())).isNull();
    }

    @Test
    @DisplayName("a zero-value note is not a collection instruction")
    void zeroValueYieldsNothing() {
        Map<Integer, BigDecimal> taxedUnit = Map.of(20, BigDecimal.ZERO);

        assertThat(OdooLookupAdapter.taxedValueOf(List.of(move(20, 4)), taxedUnit)).isNull();
    }

    @Test
    @DisplayName("fractional quantities keep their value")
    void fractionalQuantities() {
        Map<Integer, BigDecimal> taxedUnit = Map.of(20, new BigDecimal("10.500"));

        BigDecimal amount = OdooLookupAdapter.taxedValueOf(List.of(move(20, 2.5)), taxedUnit);

        assertThat(amount).isEqualByComparingTo("26.250");
    }
}
