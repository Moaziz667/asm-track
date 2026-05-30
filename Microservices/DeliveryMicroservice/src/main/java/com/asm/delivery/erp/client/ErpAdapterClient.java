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
    private final String defaultProvider;
    private final String authServerUrl;
    private final String clientId;
    private final String clientSecret;

    // Service token cache
    private String  cachedToken;
    private java.time.Instant tokenExpiresAt = java.time.Instant.MIN;

    public ErpAdapterClient(
            @Value("${erp.adapter-url:http://erp-adapter:8088}") String adapterBaseUrl,
            @Value("${erp.default-provider:odoo}") String defaultProvider,
            @Value("${auth.server.url}") String authServerUrl,
            @Value("${auth.client.id}") String clientId,
            @Value("${auth.client.secret}") String clientSecret,
            @Value("${erp.sync.timeout.connect-ms:3000}") int connectMs,
            @Value("${erp.sync.timeout.read-ms:30000}") int readMs) {

        this.adapterBaseUrl = adapterBaseUrl;
        this.defaultProvider = defaultProvider;
        this.authServerUrl  = authServerUrl;
        this.clientId       = clientId;
        this.clientSecret   = clientSecret;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    private synchronized String getServiceToken() {
        if (cachedToken != null && java.time.Instant.now().isBefore(tokenExpiresAt)) return cachedToken;
        org.springframework.util.MultiValueMap<String, String> params = new org.springframework.util.LinkedMultiValueMap<>();
        params.add("grant_type",    "client_credentials");
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<Map> resp = new RestTemplate().exchange(
                authServerUrl + "/oauth2/token", HttpMethod.POST,
                new HttpEntity<>(params, h), Map.class);
        Map<String, Object> body = resp.getBody();
        cachedToken    = (String) body.get("access_token");
        int expiresIn  = ((Number) body.get("expires_in")).intValue();
        tokenExpiresAt = java.time.Instant.now().plusSeconds(expiresIn - 30);
        return cachedToken;
    }

    // ── Sync Operations ─────────────────────────────────────────────────────────

    /**
     * Sync order cancellation via the adapter.
     */
    public boolean syncOrderCancellation(String erpOrderId, String transactionId, String erpProvider) {
        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/order-cancellation")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .queryParam("erpOrderId", erpOrderId)
                .queryParam("transactionId", transactionId)
                .toUriString();
        return postBooleanResult(url, null);
    }

    /**
     * Sync full delivery via the adapter.
     */
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId, String erpProvider) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        if (backorderPickingId != null) body.put("backorderPickingId", backorderPickingId);

        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/full-delivery")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .toUriString();
        return postBooleanResult(url, body);
    }

    /**
     * Sync partial delivery via the adapter.
     * Returns map with { success, pickingId, backorderPickingId }.
     */
    public Map<String, Object> syncPartialDelivery(String erpOrderId, List<PartialDeliveryItem> partialItems, String transactionId, String erpProvider) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
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

        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/partial-delivery")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .toUriString();

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders()),
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
    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId, String erpProvider) {
        Map<String, Object> body = new HashMap<>();
        body.put("erpOrderId", erpOrderId);
        body.put("transactionId", transactionId);
        body.put("failureCode", failureCode);
        body.put("comment", comment);

        String url = UriComponentsBuilder.fromHttpUrl(adapterBaseUrl + "/api/erp/sync/failure")
                .queryParam("erpProvider", erpProvider != null ? erpProvider : defaultProvider)
                .toUriString();
        return postBooleanResult(url, body);
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

    /**
     * Build request headers.
     */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(getServiceToken());
        return headers;
    }



    private boolean postBooleanResult(String url, Object body) {
        String erpOrderId = extractField(body, "erpOrderId");
        String transactionId = extractField(body, "transactionId");
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> result = response.getBody();
            boolean success = result != null && Boolean.TRUE.equals(result.get("success"));
            if (!success) {
                Object reason = result != null ? result.get("reason") : "null_body";
                log.error("ERP adapter success=false — erpOrderId={} txId={} url={} adapterReason={}",
                        erpOrderId, transactionId, url, reason);
            }
            return success;
        } catch (Exception e) {
            log.error("ERP adapter call failed — erpOrderId={} txId={} url={} errorClass={} reason={}",
                    erpOrderId, transactionId, url, e.getClass().getSimpleName(), e.getMessage(), e);
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
