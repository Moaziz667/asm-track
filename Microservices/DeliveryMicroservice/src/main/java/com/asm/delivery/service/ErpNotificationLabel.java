package com.asm.delivery.service;

/**
 * Single source of truth for ERP notification labels.
 * Shared by {@link OutboxProcessor} (event-type input) and
 * {@link com.asm.delivery.messaging.ErpSyncResultConsumer} (operation-code input).
 */
public final class ErpNotificationLabel {

    private ErpNotificationLabel() {}

    /**
     * Maps an ERP event type (e.g. {@code ERP_SYNC_STOCK}) <b>or</b> an operation code
     * (e.g. {@code STOCK_FULL}, {@code RETURN}) to the short label shown in admin failure notifications.
     */
    public static String of(String input) {
        if (input == null || input.isEmpty()) return "SYNC";
        return switch (input) {
            // event types (from outbox)
            case "ERP_SYNC_STOCK"        -> "STOCK";
            case "ERP_SYNC_FAILURE"      -> "FAILURE_REPORT";
            case "ERP_SYNC_CANCELLATION" -> "CANCELLATION";
            case "ERP_SYNC_POD"          -> "POD";
            case "ERP_SYNC_RETURN"       -> "RETURN";
            case "ERP_SYNC_RESCHEDULE"   -> "RESCHEDULE";
            // operation codes (from ERP result payload)
            case "STOCK_FULL", "STOCK_PARTIAL" -> "STOCK";
            case "FAILURE"                     -> "FAILURE_REPORT";
            // pass-through for codes that are already the label
            case "CANCELLATION", "POD", "RETURN", "RESCHEDULE" -> input;
            default -> "SYNC";
        };
    }
}
