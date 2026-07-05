package com.asm.delivery.entity;

/**
 * Where a configurable {@link FailureReason} may be used. Replaces the old 4-value FailureContext
 * (whose ITEM_REFUSED/ITEM_DAMAGED/ITEM_MISSING duplicated the analytics {@link FailureCode} category
 * and let a reason be mis-placed). Now the disposition is driven purely by the category, and this scope
 * only answers the one thing the category can't: is the reason usable on a whole delivery, a single
 * line item, or both.
 */
public enum ReasonScope {
    /** Only on the whole-delivery failure sheet. */
    DELIVERY,
    /** Only as a per-line item disposition (refused / damaged / missing). */
    ITEM,
    /** Both surfaces. */
    BOTH;

    public boolean coversDelivery() { return this == DELIVERY || this == BOTH; }
    public boolean coversItem()     { return this == ITEM     || this == BOTH; }
}
