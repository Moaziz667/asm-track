package com.asm.erpadapter.dto;

import java.util.Map;

/**
 * A provider-neutral "an order changed in the ERP" event, produced by an {@link com.asm.erpadapter.port.ErpChangePort}
 * implementation (Odoo, ERPNext, …) and forwarded to the delivery platform for reconciliation.
 *
 * @param erpOrderId  the ERP's order reference (Odoo {@code sale.order.name} "S00123", ERPNext SO name)
 * @param changeType  neutral change code the platform understands: {@code CANCELLED} | {@code DATE} | …
 * @param payload     change-specific fields (e.g. {@code {"scheduledAt": "2026-07-22"}}); nullable
 * @param cursorToken the provider's monotonically-increasing change stamp (Odoo {@code write_date},
 *                    ERPNext {@code modified}) — drives both the poll cursor and the platform's anti-replay
 */
public record ErpOrderChangeDTO(
        String erpOrderId,
        String changeType,
        Map<String, Object> payload,
        String cursorToken
) {}
