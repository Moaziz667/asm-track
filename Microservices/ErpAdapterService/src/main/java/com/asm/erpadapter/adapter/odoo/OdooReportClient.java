package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.service.SettingsClient;
import com.asm.erpadapter.exception.ErpAdapterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OdooReportClient {

    private final SettingsClient settingsClient;
    private final OdooJsonRpcClient rpc;
    private final RestClient restClient = RestClient.create();

    private Map<String, Object> getConf() {
        var settings = settingsClient.getSettings();
        if (settings == null || settings.getErpConfiguration() == null) {
            throw ErpAdapterException.badRequest("Configuration ERP manquante.");
        }
        return settings.getErpConfiguration();
    }

    private String getBaseUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception e) {
            return url.replace("/jsonrpc", "");
        }
    }

    public String authenticate() {
        Map<String, Object> conf = getConf();
        String login = (String) conf.get("login");
        // The report download uses Odoo's web session, which keys on the username (login),
        // whereas the rest of the integration authenticates by numeric uid + password.
        // To avoid requiring a second, separately-configured credential, derive the login
        // from the configured uid via JSON-RPC (reusing the uid+password sync already uses).
        if (login == null || login.isBlank()) {
            login = resolveLoginFromUid(conf);
        }
        if (login == null || login.isBlank()) {
            throw ErpAdapterException.badRequest(
                "Odoo login introuvable : ni 'login' configuré, ni dérivable du uid. "
                + "Vérifiez 'uid'/'password' dans la configuration ERP.");
        }

        String urlStr = (String) conf.get("url");
        String baseUrl = getBaseUrl(urlStr);
        String authUrl = baseUrl + "/web/session/authenticate";

        // Odoo accepts an API key wherever a password is expected — prefer it, fall back to password.
        Object secret = conf.get("apiKey") != null && !String.valueOf(conf.get("apiKey")).isBlank()
                ? conf.get("apiKey") : conf.get("password");
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "call",
                "params", Map.of(
                        "db", conf.get("db") != null ? conf.get("db") : "",
                        "login", login,
                        "password", secret != null ? secret : ""
                )
        );

        try {
            ResponseEntity<Map> response = restClient.post()
                    .uri(authUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (cookies != null) {
                for (String cookie : cookies) {
                    if (cookie.startsWith("session_id=")) {
                        return cookie.split(";")[0];
                    }
                }
            }
            throw ErpAdapterException.badRequest("Échec de l'authentification Odoo: aucun session_id retourné");
        } catch (ErpAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw ErpAdapterException.internal("Erreur lors de l'authentification Odoo: " + e.getMessage());
        }
    }

    /**
     * Resolve the Odoo username (login) for the configured uid via JSON-RPC, so the
     * web-session report download can reuse the same credentials as the rest of the
     * integration. Returns null if uid is unset or res.users can't be read.
     */
    private String resolveLoginFromUid(Map<String, Object> conf) {
        Object uidObj = conf.get("uid");
        Integer uid;
        try {
            uid = uidObj != null ? Integer.valueOf(String.valueOf(uidObj).trim()) : null;
        } catch (NumberFormatException e) {
            uid = null;
        }
        if (uid == null || uid <= 0) return null;
        try {
            List<Map<String, Object>> rows = rpc.searchRead(
                    "res.users",
                    List.of(List.of("id", "=", uid)),
                    List.of("id", "login"),
                    1, null);
            if (rows.isEmpty()) return null;
            return OdooJsonRpcClient.asString(rows.get(0).get("login"));
        } catch (Exception e) {
            log.warn("Could not derive Odoo login from uid {}: {}", uid, e.getMessage());
            return null;
        }
    }

    public byte[] fetchReportPdf(Integer pickingId, String cookie) {
        Map<String, Object> conf = getConf();
        String reportId = (String) conf.get("reportId");
        String reportName = reportId != null && !reportId.isBlank() ? reportId : "stock.report_deliveryslip";
        String urlStr = (String) conf.get("url");
        String baseUrl = getBaseUrl(urlStr);
        String reportUrl = baseUrl + "/report/pdf/" + reportName + "/" + pickingId;

        try {
            byte[] pdfBytes = restClient.get()
                    .uri(reportUrl)
                    .header(HttpHeaders.COOKIE, cookie)
                    .accept(MediaType.APPLICATION_PDF)
                    .retrieve()
                    .body(byte[].class);
            if (pdfBytes == null || pdfBytes.length < 4) {
                throw ErpAdapterException.internal("Réponse PDF vide ou invalide");
            }
            // Validate it looks like a PDF
            String magic = new String(pdfBytes, 0, 4);
            if (!magic.startsWith("%PDF")) {
                throw ErpAdapterException.internal("Le fichier retourné par Odoo n'est pas un PDF (session peut-être rejetée)");
            }
            return pdfBytes;
        } catch (Exception e) {
            throw ErpAdapterException.internal("Erreur lors du téléchargement du PDF Odoo: " + e.getMessage());
        }
    }
}
