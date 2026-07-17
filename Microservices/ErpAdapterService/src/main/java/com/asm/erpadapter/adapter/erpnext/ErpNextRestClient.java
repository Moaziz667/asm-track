package com.asm.erpadapter.adapter.erpnext;

import com.asm.erpadapter.exception.ErpAdapterException;
import com.asm.erpadapter.service.SettingsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Low-level ERPNext / Frappe REST client — the ERPNext counterpart of {@code OdooJsonRpcClient}.
 *
 * <p><b>Transport:</b> plain HTTP REST (no JSON-RPC). Two endpoints cover every read we need:
 * <ul>
 *   <li>{@code GET /api/method/frappe.client.get_list} — list of a DocType's <b>parent</b> fields
 *       ({@link #getList}). Child tables (e.g. Delivery Note items) are <b>not</b> returned here.</li>
 *   <li>{@code GET /api/resource/{doctype}/{name}} — the full document <b>including child tables</b>
 *       ({@link #getDoc}). Used to fetch a single order + its lines.</li>
 *   <li>{@code GET /api/method/frappe.client.get_count} — a cheap existence/count ({@link #getCount}).</li>
 * </ul>
 *
 * <p><b>Auth:</b> API key/secret pair sent as {@code Authorization: token <key>:<secret>}. The pair is
 * read fresh from {@code SystemSettings.erpConfiguration} on every call ({@code url}, {@code apiKey},
 * {@code apiSecret}) so the adapter stays <b>stateless</b> — a credential change or a future per-tenant
 * connection needs no code change here.
 *
 * <p><b>Error contract (mirrors Odoo):</b> {@link #getList}/{@link #getDoc}/{@link #getCount} catch all
 * transport/serialization errors and return a safe default (empty list / {@code null} / {@code 0}); they
 * never throw. Use {@link #getListStrict} for reads that gate a decision, where a transport error must
 * <em>not</em> be mistaken for "empty result".
 */
@Component
@Slf4j
public class ErpNextRestClient {

    private final SettingsClient settingsClient;
    private final RestClient restClient;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${allowed.erp.domains:}")
    private String allowedDomains;

    @Autowired
    public ErpNextRestClient(
            SettingsClient settingsClient,
            @Value("${erpnext.timeout.connect-ms:5000}") int connectMs,
            @Value("${erpnext.timeout.read-ms:15000}") int readMs) {
        this.settingsClient = settingsClient;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // ── Connection config (read fresh from settings every call) ──────────────────

    private String cfg(String key) {
        var c = settingsClient.getSettings().getErpConfiguration();
        return c != null && c.get(key) != null ? String.valueOf(c.get(key)).trim() : "";
    }

    private String baseUrl() {
        String url = cfg("url");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String authToken() {
        return "token " + cfg("apiKey") + ":" + cfg("apiSecret");
    }

    /** Optional company-scoping key: which company's data to read when the instance has several. */
    public String companyScope() {
        return cfg("company");
    }

    // ── SSRF guard (identical policy to OdooJsonRpcClient) ───────────────────────

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) return;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) throw new SecurityException("SSRF Guard: Invalid host in URL");
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new SecurityException("SSRF Guard: Forbidden URL scheme: " + scheme);
            }
            if (allowedDomains != null && !allowedDomains.isBlank()) {
                List<String> allowed = java.util.Arrays.stream(allowedDomains.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (!allowed.isEmpty()) {
                    boolean match = allowed.stream().anyMatch(d -> host.equalsIgnoreCase(d) || host.endsWith("." + d));
                    if (!match) {
                        throw new SecurityException("SSRF Guard: Host '" + host + "' is not whitelisted for ERP integration");
                    }
                }
            }
        } catch (Exception e) {
            log.error("SSRF Guard block: URL '{}' failed validation: {}", url, e.getMessage());
            throw new SecurityException("SSRF Guard block: " + e.getMessage(), e);
        }
    }

    // ── frappe.client.get_list — parent fields only ──────────────────────────────

    /**
     * List a DocType. {@code filters} is Frappe's JSON form, e.g. {@code [["docstatus","=",0]]}.
     * Returns parent fields only — for child tables use {@link #getDoc}. Safe: empty list on failure.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getList(String doctype, List<String> fields,
                                             List<List<Object>> filters, int limit, String orderBy) {
        try {
            return getListStrict(doctype, fields, filters, limit, orderBy);
        } catch (Exception e) {
            log.warn("ERPNext getList failed doctype={} reason={}", doctype, e.getMessage());
            return List.of();
        }
    }

    /** Like {@link #getList} but THROWS on transport error — for reads that gate a success/failure decision. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getListStrict(String doctype, List<String> fields,
                                                   List<List<Object>> filters, int limit, String orderBy) {
        String base = baseUrl();
        if (base.isBlank()) {
            log.warn("ERPNext getList abort — ERP URL is not configured in Settings!");
            return List.of();
        }
        validateUrl(base);
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(base)
                    .path("/api/method/frappe.client.get_list")
                    .queryParam("doctype", doctype)
                    .queryParam("fields", json.writeValueAsString(fields));
            if (filters != null && !filters.isEmpty()) {
                b.queryParam("filters", json.writeValueAsString(filters));
            }
            if (limit > 0) b.queryParam("limit_page_length", limit);
            if (orderBy != null && !orderBy.isBlank()) b.queryParam("order_by", orderBy);
            URI uri = b.build().encode().toUri();

            Map<String, Object> resp = restClient.get().uri(uri)
                    .header("Authorization", authToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(Map.class);
            Object msg = resp != null ? resp.get("message") : null;
            return msg instanceof List ? (List<Map<String, Object>>) msg : List.of();
        } catch (Exception e) {
            throw new ErpAdapterException("ERPNext get_list transport error for " + doctype + ": " + e.getMessage(), 503);
        }
    }

    // ── /api/resource/{doctype}/{name} — full document incl. child tables ────────

    /** Fetch a single document by name, including its child tables (items). {@code null} on failure. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDoc(String doctype, String name) {
        String base = baseUrl();
        if (base.isBlank() || name == null || name.isBlank()) return null;
        validateUrl(base);
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(base)
                    .pathSegment("api", "resource", doctype, name)
                    .build().encode().toUri();
            Map<String, Object> resp = restClient.get().uri(uri)
                    .header("Authorization", authToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(Map.class);
            Object data = resp != null ? resp.get("data") : null;
            return data instanceof Map ? (Map<String, Object>) data : null;
        } catch (Exception e) {
            log.warn("ERPNext getDoc failed {}/{}: {}", doctype, name, e.getMessage());
            return null;
        }
    }

    // ── frappe.client.get_count ──────────────────────────────────────────────────

    /** Count records matching {@code filters}. Returns 0 on failure. */
    public int getCount(String doctype, List<List<Object>> filters) {
        String base = baseUrl();
        if (base.isBlank()) return 0;
        validateUrl(base);
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(base)
                    .path("/api/method/frappe.client.get_count")
                    .queryParam("doctype", doctype);
            if (filters != null && !filters.isEmpty()) {
                b.queryParam("filters", json.writeValueAsString(filters));
            }
            URI uri = b.build().encode().toUri();
            Map<String, Object> resp = restClient.get().uri(uri)
                    .header("Authorization", authToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(Map.class);
            Integer n = resp != null ? asInt(resp.get("message")) : null;
            return n != null ? n : 0;
        } catch (Exception e) {
            log.warn("ERPNext getCount failed doctype={} reason={}", doctype, e.getMessage());
            return 0;
        }
    }

    // ── Type-safe extraction helpers (Frappe returns JSON numbers/strings) ────────

    public static String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    public static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Math.round(Double.parseDouble(String.valueOf(o))); }
        catch (NumberFormatException e) { return null; }
    }

    public static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(o)); }
        catch (NumberFormatException e) { return null; }
    }

    public static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); }
        catch (NumberFormatException e) { return null; }
    }

    public static boolean asBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(o).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }
}
