package com.asm.delivery.erp.client;

import com.asm.delivery.dto.request.PartialDeliveryItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wrapper over {@link ErpAdapterFeignClient} that keeps the defensive contract the rest of the
 * codebase relies on: every method catches transport errors, logs them, and returns a safe default
 * (false / null / empty). Service auth + caller identity are handled by the shared Feign interceptor;
 * retry/backoff is owned by {@code OutboxProcessor}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ErpAdapterClient {

    private final ErpAdapterFeignClient feign;

    @Value("${erp.default-provider:odoo}")
    private String defaultProvider;

    private String provider(String erpProvider) {
        return erpProvider != null ? erpProvider : defaultProvider;
    }

    // ── Sync Operations ─────────────────────────────────────────────────────────

    public boolean syncOrderCancellation(String erpOrderId, String transactionId, String erpProvider, String pickingRef) {
        try {
            Map<String, Object> result = feign.syncOrderCancellation(
                    provider(erpProvider), erpOrderId, transactionId,
                    (pickingRef != null && !pickingRef.isBlank()) ? pickingRef : null);
            return isSuccess(result, erpOrderId, transactionId, "order-cancellation");
        } catch (Exception e) {
            logCallFailure(erpOrderId, transactionId, "order-cancellation", e);
            return false;
        }
    }

    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId, String erpProvider, String pickingRef) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        if (backorderPickingId != null) body.put("backorderPickingId", backorderPickingId);
        if (pickingRef != null && !pickingRef.isBlank()) body.put("pickingRef", pickingRef);
        try {
            Map<String, Object> result = feign.syncFullDelivery(provider(erpProvider), body);
            return isSuccess(result, erpOrderId, transactionId, "full-delivery");
        } catch (Exception e) {
            logCallFailure(erpOrderId, transactionId, "full-delivery", e);
            return false;
        }
    }

    /** Returns map with { success, pickingId, backorderPickingId }. */
    public Map<String, Object> syncPartialDelivery(String erpOrderId, List<PartialDeliveryItem> partialItems, String transactionId, String erpProvider, String pickingRef) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        if (pickingRef != null && !pickingRef.isBlank()) body.put("pickingRef", pickingRef);
        if (partialItems != null) {
            List<Map<String, Object>> items = partialItems.stream().map(item -> {
                Map<String, Object> m = new HashMap<>();
                m.put("referenceKey", item.referenceKey());
                m.put("quantityDone", item.getQuantityDone());
                if (item.getName()    != null && !item.getName().isBlank())    m.put("itemName",  item.getName());
                if (item.getOutcome() != null)  m.put("outcome",  item.effectiveOutcome());
                if (item.getReason()  != null)  m.put("reason",   item.getReason());
                if (item.getComment() != null && !item.getComment().isBlank()) m.put("comment", item.getComment());
                return m;
            }).collect(Collectors.toList());
            body.put("items", items);
        }
        try {
            Map<String, Object> result = feign.syncPartialDelivery(provider(erpProvider), body);
            if (result == null) result = Map.of("success", false);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                log.error("Adapter syncPartialDelivery returned success=false: erpOrderId={}, response={}", erpOrderId, result);
            }
            return result;
        } catch (Exception e) {
            log.error("Adapter syncPartialDelivery failed for erpOrderId={}: {}", erpOrderId, e.getMessage(), e);
            return Map.of("success", false);
        }
    }

    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId, String erpProvider, String pickingRef) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        body.put("failureCode", failureCode);
        body.put("comment", comment);
        if (pickingRef != null && !pickingRef.isBlank()) body.put("pickingRef", pickingRef);
        try {
            Map<String, Object> result = feign.syncFailure(provider(erpProvider), body);
            return isSuccess(result, erpOrderId, transactionId, "failure");
        } catch (Exception e) {
            logCallFailure(erpOrderId, transactionId, "failure", e);
            return false;
        }
    }

    // ── Lookup Operations ───────────────────────────────────────────────────────

    public List<Map<String, Object>> searchClients(String search, int limit, String erpProvider) {
        try {
            List<Map<String, Object>> r = feign.searchClients(provider(erpProvider), search != null ? search : "", limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("Adapter searchClients failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<Map<String, Object>> searchProducts(String search, int limit, String erpProvider) {
        try {
            List<Map<String, Object>> r = feign.searchProducts(provider(erpProvider), search != null ? search : "", limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("Adapter searchProducts failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<Map<String, Object>> getPendingOrders(int limit, String erpProvider) {
        try {
            List<Map<String, Object>> r = feign.getPendingOrders(provider(erpProvider), limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("Adapter getPendingOrders failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public Map<String, Object> getPendingOrderPreview(String erpOrderId, String erpProvider) {
        try {
            return feign.getPendingOrderPreview(provider(erpProvider), erpOrderId);
        } catch (Exception e) {
            log.error("Adapter getPendingOrderPreview failed for erpOrderId={}", erpOrderId, e);
            return null;
        }
    }

    public byte[] getDeliveryNotePdf(String blNumber, String erpProvider) {
        try {
            return feign.getDeliveryNotePdf(provider(erpProvider), blNumber);
        } catch (feign.FeignException.NotFound e) {
            log.error("Adapter getDeliveryNotePdf: BL not found in Odoo: {}", blNumber);
            return null;
        } catch (feign.FeignException e) {
            log.error("Adapter getDeliveryNotePdf failed for blNumber={}: HTTP {} - {}",
                    blNumber, e.status(), e.contentUTF8());
            return null;
        } catch (Exception e) {
            log.error("Adapter getDeliveryNotePdf failed for blNumber={}", blNumber, e);
            return null;
        }
    }

    public List<Map<String, Object>> getWarehouses(String erpProvider) {
        try {
            List<Map<String, Object>> r = feign.getWarehouses(provider(erpProvider));
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("Adapter getWarehouses failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public String getPickingRef(String pickingId, String erpProvider) {
        try {
            Map<String, Object> body = feign.getPickingRef(provider(erpProvider), pickingId);
            return body != null && body.get("ref") != null ? String.valueOf(body.get("ref")) : null;
        } catch (Exception e) {
            log.warn("Adapter getPickingRef failed for pickingId={}: {}", pickingId, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getCompany(String erpProvider) {
        try {
            return feign.getCompany(provider(erpProvider));
        } catch (Exception e) {
            log.warn("Adapter getCompany failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────────

    private boolean isSuccess(Map<String, Object> result, String erpOrderId, String transactionId, String op) {
        boolean success = result != null && Boolean.TRUE.equals(result.get("success"));
        if (!success) {
            Object reason = result != null ? result.get("reason") : "null_body";
            log.error("ERP adapter success=false — erpOrderId={} txId={} op={} adapterReason={}",
                    erpOrderId, transactionId, op, reason);
        }
        return success;
    }

    private void logCallFailure(String erpOrderId, String transactionId, String op, Exception e) {
        log.error("ERP adapter call failed — erpOrderId={} txId={} op={} errorClass={} reason={}",
                erpOrderId, transactionId, op, e.getClass().getSimpleName(), e.getMessage(), e);
    }
}
