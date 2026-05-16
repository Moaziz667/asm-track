package com.asm.delivery.erp.client;

import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.entity.Order;
import com.asm.delivery.security.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST client for the ErpAdapterService microservice.
 *
 * <p>Translates delivery outcomes into adapter REST calls. All methods are defensive:
 * they catch network exceptions, log them, and return safe defaults (false, null, empty).
 * Retry logic is handled by {@code ErpSyncService} and {@code ErpSyncRetryScheduler}.
 *
 * <p><b>Timeouts:</b> configured via {@code erp.sync.timeout.*} properties.
 */
@Component
@Slf4j
public class ErpAdapterClient {

    private final RestTemplate restTemplate;
    private final String adapterBaseUrl;
    private final String internalSecret;
    private final String defaultProvider;

    public ErpAdapterClient(
            @Value("${erp.adapter-url:http://erp-adapter:8088}") String adapterBaseUrl,
            @Value("${internal.secret:asm-internal-2026}") String internalSecret,
            @Value("${erp.default-provider:odoo}") String defaultProvider,
            @Value("${erp.sync.timeout.connect-ms:3000}") int connectMs,
            @Value("${erp.sync.timeout.read-ms:30000}") int readMs) {

        this.adapterBaseUrl = adapterBaseUrl;
        this.internalSecret = internalSecret;
        this.defaultProvider = defaultProvider;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        this.restTemplate = new RestTemplate(factory);
    }

    // ── Sync Operations ─────────────────────────────────────────────────────────

    /**
     * Sync order cancellation via the adapter.
     */
    public boolean syncOrderCancellation(String erpOrderId, String transactionId, String erpProvider, String companyId) {
        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/order-cancellation")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .queryParam("erpOrderId", erpOrderId)
                .queryParam("transactionId", transactionId)
                .toUriString();
        return postBooleanResult(url, null, companyId);
    }

    /**
     * Sync full delivery via the adapter.
     */
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId, String erpProvider, String companyId) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        if (backorderPickingId != null) body.put("backorderPickingId", backorderPickingId);

        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/full-delivery")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .toUriString();
        return postBooleanResult(url, body, companyId);
    }

    /**
     * Sync partial delivery via the adapter.
     * Returns map with { success, pickingId, backorderPickingId }.
     */
    public Map<String, Object> syncPartialDelivery(String erpOrderId, List<PartialDeliveryItem> partialItems, String transactionId, String erpProvider, String companyId) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        if (partialItems != null) {
            List<Map<String, Object>> items = partialItems.stream().map(item -> {
                Map<String, Object> m = new HashMap<>();
                m.put("referenceKey", item.referenceKey());
                m.put("quantityDone", item.getQuantityDone());
                return m;
            }).collect(Collectors.toList());
            body.put("items", items);
        }

        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/partial-delivery")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .toUriString();

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders(companyId)),
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> result = response.getBody() != null ? response.getBody() : Map.of("success", false);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                log.error("Adapter syncPartialDelivery returned success=false: erpOrderId={}, response={}", erpOrderId, result);
            }
            return result;
        } catch (Exception e) {
            log.error("Adapter syncPartialDelivery failed for erpOrderId={}: {}", erpOrderId, e.getMessage(), e);
            return Map.of("success", false);
        }
    }

    /**
     * Sync failure note via the adapter.
     */
    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId, String erpProvider, String companyId) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        body.put("failureCode", failureCode);
        body.put("comment", comment);

        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/failure")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .toUriString();
        return postBooleanResult(url, body, companyId);
    }

    // ── Lookup Operations ───────────────────────────────────────────────────────

    /**
     * Search clients in the ERP.
     */
    public List<Map<String, Object>> searchClients(String search, int limit, String erpProvider) {
        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/lookup/clients")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .queryParam("search", search != null ? search : "")
                .queryParam("limit", limit)
                .toUriString();
        return getListResult(url);
    }

    /**
     * Search products in the ERP.
     */
    public List<Map<String, Object>> searchProducts(String search, int limit, String erpProvider) {
        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/lookup/products")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .queryParam("search", search != null ? search : "")
                .queryParam("limit", limit)
                .toUriString();
        return getListResult(url);
    }

    /**
     * Get pending orders from the ERP.
     */
    public List<Map<String, Object>> getPendingOrders(int limit, String erpProvider) {
        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/lookup/pending-orders")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .queryParam("limit", limit)
                .toUriString();
        return getListResult(url);
    }

    /**
     * Preview a pending order from the ERP.
     */
    public Map<String, Object> getPendingOrderPreview(String erpOrderId, String erpProvider) {
        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/lookup/pending-orders/" + erpOrderId)
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .toUriString();

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Adapter getPendingOrderPreview failed for erpOrderId={}", erpOrderId, e);
            return null;
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────────

    /** Get pending orders scoped to a specific company — safe to call from schedulers (no JWT needed). */
    public List<Map<String, Object>> getPendingOrdersForCompany(int limit, UUID companyId) {
        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/lookup/pending-orders")
                .queryParam("erpProvider", defaultProvider)
                .queryParam("limit", limit)
                .toUriString();
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeadersForCompany(companyId)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : List.of();
        } catch (Exception e) {
            log.warn("getPendingOrdersForCompany failed for company={}: {}", companyId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Build request headers.
     * When {@code companyId} is provided (outbox/scheduler path), it is used directly.
     * Otherwise falls back to the SecurityContext (controller/request path).
     */
    private HttpHeaders buildHeaders(String companyId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Secret", internalSecret);
        if (companyId != null && !companyId.isBlank()) {
            headers.set("X-Company-Id", companyId);
        } else {
            // Fallback for controller-path calls that have a SecurityContext
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal
                    && principal.getCompanyId() != null) {
                headers.set("X-Company-Id", principal.getCompanyId());
            }
        }
        return headers;
    }

    /** Convenience overload for controller-path calls (SecurityContext available). */
    private HttpHeaders buildHeaders() {
        return buildHeaders(null);
    }

    private HttpHeaders buildHeadersForCompany(UUID companyId) {
        return buildHeaders(companyId != null ? companyId.toString() : null);
    }

    private boolean postBooleanResult(String url, Object body, String companyId) {
        String erpOrderId = extractField(body, "erpOrderId");
        String transactionId = extractField(body, "transactionId");
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders(companyId)),
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> result = response.getBody();
            boolean success = result != null && Boolean.TRUE.equals(result.get("success"));
            if (!success) {
                Object reason = result != null ? result.get("reason") : "null_body";
                log.error("ERP adapter success=false — erpOrderId={} txId={} companyId={} url={} adapterReason={}",
                        erpOrderId, transactionId, companyId, url, reason);
            }
            return success;
        } catch (Exception e) {
            log.error("ERP adapter call failed — erpOrderId={} txId={} companyId={} url={} errorClass={} reason={}",
                    erpOrderId, transactionId, companyId, url, e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }

    private static String extractField(Object body, String field) {
        if (body instanceof Map<?, ?> m) {
            Object v = m.get(field);
            return v != null ? v.toString() : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getListResult(String url) {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : List.of();
        } catch (Exception e) {
            log.error("Adapter list call failed: {}", url, e);
            return List.of();
        }
    }

    private String resolveProvider(Order order) {
        // Future: per-client ERP provider routing  from order.getErpProvider()
        return defaultProvider;
    }
}
