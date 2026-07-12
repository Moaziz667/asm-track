package com.asm.delivery.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the shared ERP notification-label helper ({@link ErpNotificationLabel})
 * and the resync-op mapping on {@link OutboxProcessor}.
 */
class ErpNotificationLabelTest {

    // ── ErpNotificationLabel.of() — event types ──────────────────────────────

    @ParameterizedTest(name = "event {0} → {1}")
    @CsvSource({
            "ERP_SYNC_STOCK,        STOCK",
            "ERP_SYNC_FAILURE,      FAILURE_REPORT",
            "ERP_SYNC_CANCELLATION, CANCELLATION",
            "ERP_SYNC_POD,          POD",
            "ERP_SYNC_RETURN,       RETURN",
            "ERP_SYNC_RESCHEDULE,   RESCHEDULE"
    })
    @DisplayName("event types map to notification labels")
    void eventTypes_mapToLabels(String input, String expected) {
        assertThat(ErpNotificationLabel.of(input)).isEqualTo(expected);
    }

    // ── ErpNotificationLabel.of() — operation codes ──────────────────────────

    @ParameterizedTest(name = "op {0} → {1}")
    @CsvSource({
            "STOCK_FULL,    STOCK",
            "STOCK_PARTIAL, STOCK",
            "FAILURE,       FAILURE_REPORT",
            "CANCELLATION,  CANCELLATION",
            "POD,           POD",
            "RETURN,        RETURN",
            "RESCHEDULE,    RESCHEDULE"
    })
    @DisplayName("operation codes map to notification labels")
    void operationCodes_mapToLabels(String input, String expected) {
        assertThat(ErpNotificationLabel.of(input)).isEqualTo(expected);
    }

    // ── ErpNotificationLabel.of() — edge cases ───────────────────────────────

    @Test
    @DisplayName("null → SYNC")
    void null_returnsDefault() {
        assertThat(ErpNotificationLabel.of(null)).isEqualTo("SYNC");
    }

    @Test
    @DisplayName("empty string → SYNC")
    void empty_returnsDefault() {
        assertThat(ErpNotificationLabel.of("")).isEqualTo("SYNC");
    }

    @Test
    @DisplayName("unknown code → SYNC")
    void unknown_returnsDefault() {
        assertThat(ErpNotificationLabel.of("UNKNOWN_OP")).isEqualTo("SYNC");
    }
}
