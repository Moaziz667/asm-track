package com.asm.erpadapter.port;

/**
 * ERP Order Port — direct order operations.
 *
 * Covers: order ID resolution.
 * Clients already exist in the ERP — we never create them here.
 */
public interface ErpOrderPort {

    /**
     * Resolve an ERP order reference (e.g. "S00004") to canonical order ID.
     *
     * @param erpOrderRef reference string or numeric ID
     * @return resolved ID as string, or null
     */
    String resolveOrderId(String erpOrderRef);

    /**
     * Get human-readable order reference for an ERP order ID.
     *
     * @param erpOrderId numeric order ID
     * @return reference like "S00004", or null
     */
    String getOrderReference(String erpOrderId);
}
