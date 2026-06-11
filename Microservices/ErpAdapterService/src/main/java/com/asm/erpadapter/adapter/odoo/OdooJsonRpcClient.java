package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.exception.ErpAdapterException;
import com.asm.erpadapter.service.SettingsClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level Odoo JSON-RPC client.
 *
 * <p>Handles: {@code callRpc}, {@code searchRead}, and type-safe value extraction helpers.
 * All Odoo adapter classes use this as their low-level foundation.
 *
 * <p><b>Timeouts:</b> Configured via application properties:
 * <ul>
 *   <li>{@code odoo.timeout.connect-ms} — max time to establish TCP connection (default 5s)</li>
 *   <li>{@code odoo.timeout.read-ms}    — max time to receive response (default 15s)</li>
 * </ul>
 * These values are chosen to allow for Odoo's slow stock operations while still
 * failing fast enough to trigger retry logic.
 *
 * <p><b>Error contract:</b> {@link #callRpc} returns {@code null} on any network or
 * serialization error (never throws). Callers must treat {@code null} as failure.
 */
@Component
@Slf4j
public class OdooJsonRpcClient {

    private final SettingsClient settingsClient;
    private final RestClient restClient;

    @org.springframework.beans.factory.annotation.Value("${allowed.erp.domains:}")
    private String allowedDomains;

    @org.springframework.beans.factory.annotation.Autowired
    public OdooJsonRpcClient(
            SettingsClient settingsClient,
            @org.springframework.beans.factory.annotation.Value("${odoo.timeout.connect-ms:5000}") int connectMs,
            @org.springframework.beans.factory.annotation.Value("${odoo.timeout.read-ms:15000}") int readMs) {

        this.settingsClient = settingsClient;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) return;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) throw new SecurityException("SSRF Guard: Invalid host in URL");
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new SecurityException("SSRF Guard: Forbidden URL scheme: " + scheme);
            }
            if (allowedDomains != null && !allowedDomains.isBlank()) {
                java.util.List<String> allowed = java.util.Arrays.stream(allowedDomains.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
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
    
    private String getSettingStr(String key) {
        var cfg = settingsClient.getSettings().getErpConfiguration();
        return cfg != null && cfg.get(key) != null ? String.valueOf(cfg.get(key)) : "";
    }

    private int getSettingInt(String key) {
        var cfg = settingsClient.getSettings().getErpConfiguration();
        return cfg != null && cfg.get(key) != null ? Integer.parseInt(String.valueOf(cfg.get(key))) : 0;
    }

    /**
     * The secret sent to Odoo in {@code execute_kw}. Odoo accepts an API key anywhere a password is
     * expected, so we prefer {@code apiKey} when configured and fall back to {@code password} for
     * legacy configs. Using an API key (revocable, per-user) is the recommended integration credential.
     */
    private String getSecret() {
        String apiKey = getSettingStr("apiKey");
        return !apiKey.isBlank() ? apiKey : getSettingStr("password");
    }

    // ── uid resolution (login + API key → uid via common.authenticate) ──────────
    // The admin configures login + apiKey, not the internal numeric uid. We resolve it once via Odoo's
    // common.authenticate and cache it. Cache key = db|login|secret so a credential change re-resolves.

    private final java.util.concurrent.atomic.AtomicReference<String> cachedUidKey =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger cachedUid =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Returns the Odoo uid for the current credentials. Order of resolution:
     *   1. an explicitly configured {@code uid} (legacy/back-compat) — used as-is;
     *   2. otherwise resolve from {@code login} + secret via {@code common.authenticate}, cached.
     * Throws {@link ErpAdapterException} (retryable) if authentication fails so callers don't mistake
     * an auth problem for "not found".
     */
    private int resolveUid() {
        int configured = getSettingInt("uid");
        if (configured > 0) return configured;

        String db = getSettingStr("db");
        String login = getSettingStr("login");
        String secret = getSecret();
        String key = db + "|" + login + "|" + secret.hashCode();
        if (key.equals(cachedUidKey.get()) && cachedUid.get() > 0) {
            return cachedUid.get();
        }
        int uid = authenticate(db, login, secret);
        if (uid <= 0) {
            throw new ErpAdapterException(
                    "Odoo authentication failed — check login/API key in ERP settings (db=" + db + ", login=" + login + ")", 502);
        }
        cachedUid.set(uid);
        cachedUidKey.set(key);
        log.info("Odoo uid resolved via common.authenticate — db={} login={} uid={}", db, login, uid);
        return uid;
    }

    /** Invalidate the cached uid (e.g. after a credential change). */
    public void invalidateAuthCache() {
        cachedUidKey.set(null);
        cachedUid.set(0);
    }

    /**
     * Calls Odoo's {@code common.authenticate(db, login, secret, {})} over JSON-RPC and returns the
     * numeric uid (0/negative on failure). Pure API call — no DB access.
     */
    @SuppressWarnings("unchecked")
    public int authenticate(String db, String login, String secret) {
        if (db == null || db.isBlank() || login == null || login.isBlank()) return 0;
        String url = getSettingStr("url");
        if (url.isBlank()) return 0;
        validateUrl(url);

        Map<String, Object> params = new HashMap<>();
        params.put("service", "common");
        params.put("method", "authenticate");
        params.put("args", List.of(db, login, secret != null ? secret : "", Map.of()));

        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", "call");
        body.put("params", params);

        try {
            Map<String, Object> resp = restClient.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) return 0;
            Integer uid = asInt(resp.get("result"));   // Odoo returns false (→ null) on bad creds
            return uid != null ? uid : 0;
        } catch (Exception e) {
            log.warn("Odoo common.authenticate transport error — db={} login={} reason={}", db, login, e.getMessage());
            return 0;
        }
    }

    // ── Core JSON-RPC call ──────────────────────────────────────────────────

    /**
     * Execute a JSON-RPC call to Odoo's {@code /jsonrpc} endpoint.
     *
     * @param executeKwArgs args for {@code execute_kw}: [db, uid, password, model, method, args, kwargs?]
     * @return the full JSON-RPC response map, or {@code null} on any network/timeout error
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callRpc(List<Object> executeKwArgs) {
        Map<String, Object> params = new HashMap<>();
        params.put("service", "object");
        params.put("method", "execute_kw");
        params.put("args", executeKwArgs);

        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", "call");
        body.put("params", params);

        try {
            String url = getSettingStr("url");
            if (url.isBlank()) {
                log.warn("Odoo RPC abort - ERP URL is not configured in Settings!");
                return null;
            }
            validateUrl(url);
            return restClient.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            // Swallow transport errors — callers check for null and treat as failure.
            List<?> args = (List<?>) params.get("args");
            String model = (args != null && args.size() > 3) ? String.valueOf(args.get(3)) : "unknown";
            String method = (args != null && args.size() > 4) ? String.valueOf(args.get(4)) : "unknown";
            log.warn("Odoo RPC transport error — provider=odoo model={} method={} errorClass={} reason={}",
                    model, method, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * V3.1 — Like {@link #callRpc} but THROWS on a transport/timeout error instead of returning null.
     * Use this for reads that gate a success/failure decision (e.g. "does a picking exist?"), so a
     * network timeout is never mistaken for "not found → nothing to do → success". The empty-but-valid
     * Odoo response ({@code result: []}) is still returned normally; only transport failures throw.
     */
    public Map<String, Object> callRpcOrThrow(List<Object> executeKwArgs) {
        Map<String, Object> resp = callRpc(executeKwArgs);
        if (resp == null) {
            throw new ErpAdapterException("Odoo RPC transport error (null response) — treat as retryable", 503);
        }
        return resp;
    }

    /**
     * Build standard {@code execute_kw} args: {@code [db, uid, password, model, method, positionalArgs]}.
     */
    public List<Object> buildArgs(String model, String method, List<Object> positionalArgs) {
        return List.of(getSettingStr("db"), resolveUid(), getSecret(),
                model, method, positionalArgs);
    }

    /**
     * Build {@code execute_kw} args with keyword arguments (kwargs).
     */
    public List<Object> buildArgs(String model, String method, List<Object> positionalArgs,
                                  Map<String, Object> kwargs) {
        return List.of(getSettingStr("db"), resolveUid(), getSecret(),
                model, method, positionalArgs, kwargs);
    }

    // ── Generic search_read ─────────────────────────────────────────────────

    /**
     * Search + read records from an Odoo model in two steps for reliable results.
     *
     * @param model  Odoo model name (e.g. {@code "sale.order"})
     * @param domain Odoo domain filter (e.g. {@code [["state","=","sale"]]})
     * @param fields list of field names to fetch, or empty for all
     * @param limit  maximum records to return (0 = no limit)
     * @param order  order clause (e.g. {@code "id desc"}) or null
     * @return list of record maps; empty list if Odoo is down or no records found
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchRead(String model, List<Object> domain,
                                                 List<String> fields, int limit, String order) {
        try {
            Map<String, Object> searchKwargs = new HashMap<>();
            if (limit > 0) searchKwargs.put("limit", limit);
            if (order != null && !order.isBlank()) searchKwargs.put("order", order);

            Map<String, Object> searchResponse = callRpc(
                    buildArgs(model, "search", List.of(domain != null ? domain : List.of()), searchKwargs));
            if (searchResponse == null || searchResponse.containsKey("error")) {
                Object odooError = searchResponse != null ? searchResponse.get("error") : "null_response";
                log.warn("Odoo RPC error — provider=odoo model={} method=search odooError={}", model, odooError);
                return List.of();
            }

            Object searchResult = searchResponse.get("result");
            if (!(searchResult instanceof List<?> rawIds)) return List.of();

            List<Integer> ids = new ArrayList<>();
            for (Object rawId : rawIds) {
                if (rawId instanceof Number n) ids.add(n.intValue());
            }
            if (ids.isEmpty()) return List.of();

            Map<String, Object> readKwargs = new HashMap<>();
            if (fields != null && !fields.isEmpty()) readKwargs.put("fields", fields);

            Map<String, Object> readResponse = callRpc(
                    buildArgs(model, "read", List.of(ids), readKwargs));
            if (readResponse == null || readResponse.containsKey("error")) {
                Object odooError = readResponse != null ? readResponse.get("error") : "null_response";
                log.warn("Odoo RPC error — provider=odoo model={} method=read ids={} odooError={}", model, ids, odooError);
                return List.of();
            }

            Object readResult = readResponse.get("result");
            if (readResult instanceof List<?> list) return (List<Map<String, Object>>) list;
            return List.of();
        } catch (Exception e) {
            log.warn("Odoo RPC exception — provider=odoo model={} method=searchRead errorClass={} reason={}",
                    model, e.getClass().getSimpleName(), e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Strict variant of {@link #searchRead}: raises a structured {@link ErpAdapterException}
     * when Odoo is unreachable or rejects the request (bad field/model/access), instead of
     * silently returning an empty list. Use for operator-facing flows where the cause must
     * be surfaced clearly. A genuinely empty result still returns an empty list.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchReadStrict(String model, List<Object> domain,
                                                       List<String> fields, int limit, String order) {
        Map<String, Object> searchKwargs = new HashMap<>();
        if (limit > 0) searchKwargs.put("limit", limit);
        if (order != null && !order.isBlank()) searchKwargs.put("order", order);

        Map<String, Object> searchResponse = callRpc(
                buildArgs(model, "search", List.of(domain != null ? domain : List.of()), searchKwargs));
        requireOk(searchResponse, model, "search", domain);

        Object searchResult = searchResponse.get("result");
        if (!(searchResult instanceof List<?> rawIds)) return List.of();
        List<Integer> ids = new ArrayList<>();
        for (Object rawId : rawIds) if (rawId instanceof Number n) ids.add(n.intValue());
        if (ids.isEmpty()) return List.of();

        Map<String, Object> readKwargs = new HashMap<>();
        if (fields != null && !fields.isEmpty()) readKwargs.put("fields", fields);
        Map<String, Object> readResponse = callRpc(buildArgs(model, "read", List.of(ids), readKwargs));
        requireOk(readResponse, model, "read", domain);

        Object readResult = readResponse.get("result");
        return readResult instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** Throws a structured error if the Odoo response is missing or carries an error payload. */
    private void requireOk(Map<String, Object> response, String model, String method, Object domain) {
        if (response == null) {
            throw ErpAdapterException.internal(
                    "Odoo unreachable or not configured — model=" + model + " method=" + method
                    + " (check ERP URL/credentials in Settings)");
        }
        if (response.containsKey("error")) {
            throw new ErpAdapterException(
                    "Odoo rejected the request — model=" + model + " method=" + method
                    + " domain=" + domain + " — " + extractOdooError(response.get("error")), 502);
        }
    }

    /** Pulls the most useful human message out of an Odoo JSON-RPC error object. */
    private static String extractOdooError(Object error) {
        if (error instanceof Map<?, ?> m) {
            Object data = m.get("data");
            if (data instanceof Map<?, ?> dm) {
                Object msg = dm.get("message");
                if (msg != null && !String.valueOf(msg).isBlank()) return String.valueOf(msg);
                Object name = dm.get("name");
                if (name != null) return String.valueOf(name);
            }
            Object message = m.get("message");
            if (message != null) return String.valueOf(message);
        }
        return String.valueOf(error);
    }

    // ── Type-safe value helpers ─────────────────────────────────────────────

    public static Integer asInt(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    public static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    public static BigDecimal asBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof Boolean b && !b) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return BigDecimal.ZERO; }
    }

    public static String asString(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** Extract ID from an Odoo many2one field: {@code [id, "name"]} or a plain {@link Number}. */
    public static Integer asRelId(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof List<?> rel && !rel.isEmpty() && rel.get(0) instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /** Extract name from an Odoo many2one field: {@code [id, "name"]}. */
    public static String asRelName(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof List<?> rel && rel.size() > 1 && rel.get(1) != null) {
            return asString(rel.get(1));
        }
        return null;
    }

    /** Extract list of IDs from an Odoo one2many / many2many field. */
    public static List<Integer> asIdList(Object value) {
        if (!(value instanceof java.util.Collection<?> c)) return List.of();
        List<Integer> ids = new ArrayList<>();
        for (Object raw : c) {
            Integer id = asInt(raw);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    public static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
