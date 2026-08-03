package com.asm.delivery.entity;

/** How the customer settled at the door. */
public enum CashMethod {
    /** Banknotes handed to the driver — the case the whole custody chain exists for. */
    CASH,
    /**
     * A cheque. Physically it travels with the driver like cash and is handed over the same way, but
     * it is a promise rather than a settlement, which is why the number, bank and date are recorded:
     * a bounced cheque with none of those cannot be traced back to the delivery that took it.
     */
    CHEQUE,
    /** Nothing was collected. Always accompanied by a reason. */
    NONE
}
