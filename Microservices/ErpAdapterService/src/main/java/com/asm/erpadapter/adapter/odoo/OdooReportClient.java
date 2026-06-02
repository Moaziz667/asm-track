package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.service.SettingsClient;
import com.asm.erpadapter.exception.ErpAdapterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OdooReportClient {

    private final SettingsClient settingsClient;
    private final RestTemplate restTemplate = new RestTemplate();

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
        if (login == null || login.isBlank()) {
            throw ErpAdapterException.badRequest("Odoo login/username manquant dans la configuration ERP");
        }

        String urlStr = (String) conf.get("url");
        String baseUrl = getBaseUrl(urlStr);
        String authUrl = baseUrl + "/web/session/authenticate";

        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "call",
                "params", Map.of(
                        "db", conf.get("db") != null ? conf.get("db") : "",
                        "login", login,
                        "password", conf.get("password") != null ? conf.get("password") : ""
                )
        );

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(authUrl, body, Map.class);
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

    public byte[] fetchReportPdf(Integer pickingId, String cookie) {
        Map<String, Object> conf = getConf();
        String reportId = (String) conf.get("reportId");
        String reportName = reportId != null && !reportId.isBlank() ? reportId : "stock.report_deliveryslip";
        String urlStr = (String) conf.get("url");
        String baseUrl = getBaseUrl(urlStr);
        String reportUrl = baseUrl + "/report/pdf/" + reportName + "/" + pickingId;

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add(HttpHeaders.ACCEPT, "application/pdf");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(reportUrl, HttpMethod.GET, request, byte[].class);
            byte[] pdfBytes = response.getBody();
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
