package com.asm.delivery.controller;

import com.asm.delivery.erp.ErpInboundReconciliationService;
import com.asm.delivery.erp.ErpInboundReconciliationService.ChangeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * V2 — Inbound channel for Odoo→ASM changes. The ErpAdapter poller reaches ASM through this endpoint
 * with a service token — so it lives under {@code /internal/**} (SERVICE role, see SecurityConfig).
 * The PICKED_UP conflict rule and anti-replay live in {@link ErpInboundReconciliationService}.
 */
@RestController
@RequestMapping("/internal/erp")
@RequiredArgsConstructor
@Slf4j
public class InternalErpInboundController {

    private final ErpInboundReconciliationService reconciliation;

    @PostMapping("/order-changed")
    public ResponseEntity<Void> orderChanged(@RequestBody Map<String, Object> body) {
        String erpOrderId = str(body.get("erpOrderId"));
        String changeTypeRaw = str(body.get("changeType"));
        if (erpOrderId == null || changeTypeRaw == null) {
            log.warn("ERP inbound: missing erpOrderId/changeType, dropping: {}", body);
            return ResponseEntity.badRequest().build();
        }
        ChangeType changeType;
        try {
            changeType = ChangeType.valueOf(changeTypeRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("ERP inbound: unknown changeType={}, dropping", changeTypeRaw);
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        LocalDateTime writeDate = parseDate(str(body.get("erpWriteDate")));

        reconciliation.apply(erpOrderId, changeType, payload, writeDate);
        return ResponseEntity.ok().build();
    }

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    /** Odoo write_date is "YYYY-MM-DD HH:MM:SS"; tolerate ISO 'T' too. Null-safe. */
    private static LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String norm = s.trim().replace(' ', 'T');
            return LocalDateTime.parse(norm.length() >= 19 ? norm.substring(0, 19) : norm);
        } catch (Exception e) {
            return null;
        }
    }
}
