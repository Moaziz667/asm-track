package com.asm.delivery.entity;

/**
 * Where a configurable {@link FailureReason} may be offered to the driver. Independent of the
 * analytics {@link FailureCode} category: one motif can apply to several contexts.
 *
 * <ul>
 *   <li>{@code FAILURE}      — the full-visit failure sheet (delivery failed outright).</li>
 *   <li>{@code ITEM_REFUSED} — a single line the customer refused.</li>
 *   <li>{@code ITEM_DAMAGED} — a single line delivered damaged.</li>
 *   <li>{@code ITEM_MISSING} — a line not delivered for lack of stock / not loaded / lost,
 *       including short-quantity lines.</li>
 * </ul>
 */
public enum FailureContext {
    FAILURE,
    ITEM_REFUSED,
    ITEM_DAMAGED,
    ITEM_MISSING
}
