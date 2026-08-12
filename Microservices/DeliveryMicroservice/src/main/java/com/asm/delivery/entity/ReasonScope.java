package com.asm.delivery.entity;

/**
 * Where a configurable {@link FailureReason} may be used. Replaces the old 4-value FailureContext
 * (whose ITEM_REFUSED/ITEM_DAMAGED/ITEM_MISSING duplicated the analytics {@link FailureCode} category
 * and let a reason be mis-placed). Now the disposition is driven purely by the category, and this scope
 * only answers the one thing the category can't: is the reason usable on a whole delivery, a single
 * line item, on the money collected, or several of those.
 */
public enum ReasonScope {
    /** Only on the whole-delivery failure sheet. */
    DELIVERY,
    /** Only as a per-line item disposition (refused / damaged / missing). */
    ITEM,
    /** Both of the two surfaces above. Predates {@link #PAYMENT} and deliberately excludes it. */
    BOTH,
    /**
     * Only on the cash-collection card: why the driver took less than he was told to.
     *
     * <p>Added because that card filtered on {@link #coversDelivery()} and so offered the entire
     * delivery catalogue — a driver reporting a shortfall had to pick between "adresse introuvable",
     * "panne du véhicule" and "conditions météo", none of which says anything about money. The two
     * reasons that did fit were buried among twenty that did not.
     *
     * <p>Kept as a code rather than a free-text box on purpose: a code aggregates ("38 % of refusals
     * are for lack of cash"), a sentence does not.
     */
    PAYMENT;

    public boolean coversDelivery() { return this == DELIVERY || this == BOTH; }
    public boolean coversItem()     { return this == ITEM     || this == BOTH; }
    public boolean coversPayment()  { return this == PAYMENT; }
}
