package com.asm.erpadapter.adapter.odoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.asInt;
import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.asString;

/**
 * Fetches a QWeb report rendered <b>by Odoo</b> as PDF.
 *
 * <h2>Why this is not a JSON-RPC call</h2>
 * Odoo renders PDFs through {@code ir.actions.report}. The rendering method was public
 * ({@code render_qweb_pdf}) up to Odoo 13 and was renamed to {@code _render_qweb_pdf} from Odoo 14 —
 * and Odoo's RPC layer refuses to dispatch any method whose name starts with an underscore. So on
 * every version this platform actually targets (16 → 19) the report <b>cannot</b> be obtained over
 * {@code execute_kw} at all. The supported route is the web one: authenticate a session, then GET
 * {@code /report/pdf/<report_name>/<ids>}.
 *
 * <h2>The credential catch</h2>
 * {@code /web/session/authenticate} is the browser login endpoint and does not accept API keys —
 * only a real password. But an API key is precisely the credential we recommend for the RPC path
 * (revocable, per-user). A tenant configured the recommended way therefore has no password for this
 * call. We try the password first, fall back to the key, and — critically — <b>verify the response is
 * actually a PDF</b>: the documented failure mode is Odoo answering {@code 200 OK} with the HTML of
 * its login page, which would otherwise be stored and served as a "delivery note".
 *
 * <p>Everything returns {@code null} on failure. The caller degrades to a clear operator message; a
 * missing report never blocks a delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooReportService {

    private final OdooJsonRpcClient rpc;
    private final OdooVersionResolver versionResolver;

    /** Report rendering runs wkhtmltopdf server-side and is far slower than a data call. */
    @Value("${odoo.timeout.report-ms:60000}")
    private int reportTimeoutMs;

    private RestClient http;

    private RestClient http() {
        if (http == null) {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(10_000);
            f.setReadTimeout(reportTimeoutMs);
            http = RestClient.builder().requestFactory(f).build();
        }
        return http;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Render the PDF Odoo produces for a record of {@code model}.
     *
     * @param model      Odoo model, e.g. {@code "stock.picking"} or {@code "account.move"}
     * @param recordId   the record's Odoo id
     * @param preferHint substring preferred when the instance defines several reports for the model
     *                   (e.g. {@code "deliveryslip"}); the first report wins when nothing matches
     * @return raw PDF bytes, or {@code null} when unavailable for any reason
     */
    public byte[] renderPdf(String model, Integer recordId, String preferHint) {
        if (model == null || recordId == null || recordId <= 0) return null;

        String reportName = resolveReportName(model, preferHint);
        if (reportName == null) {
            log.warn("provider=odoo operation=renderPdf model={} reason=no_qweb_pdf_report_on_instance", model);
            return null;
        }

        // Odoo ≤ 13 still exposes the renderer over RPC — cheaper and credential-agnostic, so prefer it.
        int major = versionResolver.major();
        if (major > 0 && major <= 13) {
            byte[] viaRpc = renderOverRpc(reportName, recordId);
            if (viaRpc != null) return viaRpc;
        }

        return renderOverWebSession(reportName, recordId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Report discovery
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ask the instance which report it uses, instead of hardcoding {@code stock.report_deliveryslip}.
     *
     * <p>Two reasons. The technical name has moved between versions before, and — more common in
     * practice — a Tunisian tenant whose accountant added the legal mentions did so by <em>replacing</em>
     * the delivery-slip report. Hardcoding would quietly hand back the stock template and lose exactly
     * the mentions that made the document compliant.
     */
    @SuppressWarnings("unchecked")
    private String resolveReportName(String model, String preferHint) {
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("ir.actions.report", "search_read",
                    List.of(List.of(
                            List.of("model", "=", model),
                            List.of("report_type", "=", "qweb-pdf"))),
                    Map.of("fields", List.of("report_name", "name"), "limit", 20, "order", "id asc")));
            if (resp == null || resp.containsKey("error")) return null;
            Object result = resp.get("result");
            if (!(result instanceof List<?> rows) || rows.isEmpty()) return null;

            String first = null;
            for (Object raw : rows) {
                if (!(raw instanceof Map<?, ?> row)) continue;
                String reportName = asString(((Map<String, Object>) row).get("report_name"));
                if (reportName == null) continue;
                if (first == null) first = reportName;
                if (preferHint != null && reportName.toLowerCase().contains(preferHint.toLowerCase())) {
                    return reportName;
                }
            }
            return first;
        } catch (Exception e) {
            log.warn("provider=odoo operation=resolveReportName model={} reason={}", model, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Path A — RPC (Odoo ≤ 13 only)
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private byte[] renderOverRpc(String reportName, Integer recordId) {
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(
                    "ir.actions.report", "render_qweb_pdf",
                    List.of(reportName, List.of(recordId))));
            if (resp == null || resp.containsKey("error")) return null;
            Object result = resp.get("result");
            // Returns [<base64 pdf>, "pdf"].
            if (result instanceof List<?> pair && !pair.isEmpty()) {
                String b64 = asString(pair.get(0));
                if (b64 != null) {
                    byte[] pdf = java.util.Base64.getDecoder().decode(b64);
                    return looksLikePdf(pdf) ? pdf : null;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("provider=odoo operation=renderOverRpc report={} reason={}", reportName, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Path B — web session (Odoo 14+)
    // ══════════════════════════════════════════════════════════════════════════

    private byte[] renderOverWebSession(String reportName, Integer recordId) {
        String base = baseUrl();
        if (base == null) return null;

        // Password first — the only credential Odoo documents for session login. The API key is still
        // worth one request: some versions let it through res.users._check_credentials, and a tenant
        // configured the recommended way (key, no password) has nothing else to offer.
        String sessionId = openSession(base, rpc.password());
        if (sessionId == null) sessionId = openSession(base, rpc.apiKey());
        if (sessionId == null) {
            log.warn("provider=odoo operation=renderPdf report={} reason=session_auth_failed "
                    + "hint=/web/session/authenticate needs a password, an API key is not accepted", reportName);
            return null;
        }

        String url = base + "/report/pdf/" + reportName + "/" + recordId;
        try {
            rpc.assertUrlAllowed(url);
            byte[] body = http().get().uri(url)
                    .header("Cookie", "session_id=" + sessionId)
                    .header("Accept", "application/pdf")
                    .retrieve().body(byte[].class);

            if (!looksLikePdf(body)) {
                // Odoo answers 200 with its login page when the session is not accepted for this record.
                log.warn("provider=odoo operation=renderPdf report={} id={} reason=not_a_pdf "
                        + "hint=session rejected or report access denied", reportName, recordId);
                return null;
            }
            return body;
        } catch (Exception e) {
            log.warn("provider=odoo operation=renderPdf report={} id={} reason={}",
                    reportName, recordId, e.getMessage());
            return null;
        }
    }

    /** POST /web/session/authenticate and pull {@code session_id} out of the Set-Cookie header. */
    @SuppressWarnings("unchecked")
    private String openSession(String base, String secret) {
        if (secret == null || secret.isBlank()) return null;
        String url = base + "/web/session/authenticate";
        try {
            rpc.assertUrlAllowed(url);
            var response = http().post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "jsonrpc", "2.0",
                            "method", "call",
                            "params", Map.of(
                                    "db", rpc.db(),
                                    "login", rpc.login(),
                                    "password", secret)))
                    .retrieve()
                    .toEntity(Map.class);

            Map<String, Object> body = response.getBody();
            // Odoo answers 200 with an "error" payload on bad credentials.
            if (body == null || body.containsKey("error")) return null;
            Object result = body.get("result");
            if (result instanceof Map<?, ?> r && asInt(((Map<String, Object>) r).get("uid")) == null) {
                return null; // authenticated=false → uid is false
            }

            List<String> cookies = response.getHeaders().get("Set-Cookie");
            if (cookies == null) return null;
            for (String c : cookies) {
                int i = c.indexOf("session_id=");
                if (i < 0) continue;
                String rest = c.substring(i + "session_id=".length());
                int end = rest.indexOf(';');
                String value = end >= 0 ? rest.substring(0, end) : rest;
                if (!value.isBlank()) return value;
            }
            return null;
        } catch (Exception e) {
            log.debug("provider=odoo operation=openSession reason={}", e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Derive the instance root from the configured RPC endpoint.
     *
     * <p>The setting points at {@code …/jsonrpc}; the report routes hang off the site root. Anything
     * derived here is re-checked against the SSRF allow-list by the caller.
     */
    String baseUrl() {
        String url = rpc.rpcUrl();
        if (url == null || url.isBlank()) return null;
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        for (String suffix : List.of("/jsonrpc", "/xmlrpc/2/object", "/xmlrpc/2/common", "/xmlrpc")) {
            if (trimmed.endsWith(suffix)) {
                return trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }
        return trimmed;
    }

    /** A PDF always starts with {@code %PDF}. Guards against Odoo's login page being stored as a document. */
    static boolean looksLikePdf(byte[] body) {
        if (body == null || body.length < 5) return false;
        String head = new String(body, 0, 4, StandardCharsets.US_ASCII);
        return "%PDF".equals(head);
    }
}
