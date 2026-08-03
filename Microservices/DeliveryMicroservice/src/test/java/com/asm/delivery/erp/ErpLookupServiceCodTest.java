package com.asm.delivery.erp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate between "the ERP said to collect money" and "a driver will ask a customer for money".
 *
 * <p>Everything here fails closed. The two outcomes are not symmetric: an uncollected payment is an
 * invoice to chase, while a wrongly collected one is cash in the wrong hands, an argument with the
 * customer, and a discrepancy nobody can settle at the depot.
 */
class ErpLookupServiceCodTest {

    private static ErpPendingOrderPreviewDTO preview(Boolean cod, String currency, BigDecimal amount) {
        ErpPendingOrderPreviewDTO p = new ErpPendingOrderPreviewDTO();
        p.setCodRequired(cod);
        p.setCurrency(currency);
        p.setCodAmount(amount);
        return p;
    }

    @Test
    @DisplayName("collects when the ERP asks, in dinars, for a positive amount")
    void collectsOnTheHappyPath() {
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "TND", new BigDecimal("6000.000")), "WH/OUT/1")).isTrue();
    }

    @Test
    @DisplayName("does not collect when the ERP says nothing")
    void doesNotCollectByDefault() {
        assertThat(ErpLookupService.resolveCodRequired(
                preview(null, "TND", new BigDecimal("6000.000")), "WH/OUT/1")).isFalse();
        assertThat(ErpLookupService.resolveCodRequired(
                preview(false, "TND", new BigDecimal("6000.000")), "WH/OUT/1")).isFalse();
    }

    @Test
    @DisplayName("refuses a foreign currency — a driver cannot count USD into his hand")
    void refusesForeignCurrency() {
        // Not hypothetical: orders already import as "6000.000 USD" because amount and currency are
        // copied from the ERP unchecked. Harmless on a printed document, unrecoverable as cash.
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "USD", new BigDecimal("6000.000")), "WH/OUT/00342")).isFalse();
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "EUR", new BigDecimal("6000.000")), "WH/OUT/1")).isFalse();
    }

    @Test
    @DisplayName("treats a missing currency as dinars, matching the import default")
    void missingCurrencyMeansDinars() {
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, null, new BigDecimal("10.000")), "WH/OUT/1")).isTrue();
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "  ", new BigDecimal("10.000")), "WH/OUT/1")).isTrue();
    }

    @Test
    @DisplayName("accepts the currency whatever its case")
    void currencyIsCaseInsensitive() {
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "tnd", new BigDecimal("10.000")), "WH/OUT/1")).isTrue();
    }

    @Test
    @DisplayName("refuses an absent, zero or negative amount")
    void refusesNonPositiveAmount() {
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "TND", null), "WH/OUT/1")).isFalse();
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "TND", BigDecimal.ZERO), "WH/OUT/1")).isFalse();
        assertThat(ErpLookupService.resolveCodRequired(
                preview(true, "TND", new BigDecimal("-5.000")), "WH/OUT/1")).isFalse();
    }
}
