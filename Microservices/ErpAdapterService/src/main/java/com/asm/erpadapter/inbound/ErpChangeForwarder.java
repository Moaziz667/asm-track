package com.asm.erpadapter.inbound;

import com.asm.erpadapter.client.DeliveryInboundClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * V2 — Forwards an Odoo→ASM order change to the delivery platform. The inbound poller (ErpChangePoller)
 * calls {@link #forward}; ASM applies the PICKED_UP conflict rule + anti-replay.
 * Best-effort: a failure here is logged, not thrown — the next poll will re-deliver it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpChangeForwarder {

    private final DeliveryInboundClient deliveryInbound;

    /**
     * @param erpOrderId    Odoo sale-order reference (e.g. "S00123")
     * @param changeType    CANCELLED | LINES | ADDRESS | DATE
     * @param payload       change-specific fields (nullable)
     * @param odooWriteDate Odoo write_date "YYYY-MM-DD HH:MM:SS" for anti-replay (nullable)
     */
    public void forward(String erpOrderId, String changeType, Map<String, Object> payload, String odooWriteDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("changeType", changeType);
        if (payload != null) body.put("payload", payload);
        if (odooWriteDate != null) body.put("odooWriteDate", odooWriteDate);
        try {
            deliveryInbound.orderChanged(body);
            log.info("ERP change forwarded — erpOrderId={} changeType={}", erpOrderId, changeType);
        } catch (Exception e) {
            log.warn("ERP change forward failed — erpOrderId={} changeType={} reason={} (polling will retry)",
                    erpOrderId, changeType, e.getMessage());
        }
    }
}
