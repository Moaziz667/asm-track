package com.asm.delivery.odoo.sync;

import com.asm.delivery.entity.Order;

/**
 * Strategy interface for handling different Odoo synchronization scenarios.
 */
public interface OdooSyncStrategy {
    
    /**
     * Executes the specific Odoo sync logic for the given order.
     *
     * @param order the delivery order entity
     * @return true if synchronization successfully completed or appropriately handled, false otherwise.
     */
    boolean sync(Order order);
}
