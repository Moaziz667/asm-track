package com.asm.erpadapter.inbound;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V2 / Channel A — Webhook called by Odoo (via an automation rule) when a sale order changes, for
 * real-time Odoo→ASM sync. Maps the Odoo payload to a normalized change and forwards it to ASM.
 *
 * <p>Auth: a shared secret header ({@code erp.webhook.secret}) — Odoo cannot present a service JWT, so
 * we gate this single public endpoint with a secret instead. The downstream call to ASM still uses the
 * service token. The polling fallback covers any webhook that is lost or rejected.
 */
@RestController
@RequestMapping("/api/erp/inbound")
@RequiredArgsConstructor
@Slf4j
public class ErpWebhookController {

    private final ErpChangeForwarder forwarder;

    @Value("${erp.webhook.secret:}")
    private String webhookSecret;

    @PostMapping("/order-changed")
    public ResponseEntity<Void> orderChanged(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body) {

        if (webhookSecret != null && !webhookSecret.isBlank() && !webhookSecret.equals(secret)) {
            log.warn("ERP webhook: bad/missing secret — rejecting");
            return ResponseEntity.status(401).build();
        }

        String erpOrderId = str(body.get("erpOrderId"));
        String changeType = str(body.get("changeType"));
        if (erpOrderId == null || changeType == null) {
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;

        forwarder.forward(erpOrderId, changeType.toUpperCase(), payload, str(body.get("odooWriteDate")));
        return ResponseEntity.ok().build();
    }

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }
}
