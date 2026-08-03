package com.asm.erpadapter.adapter.odoo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers the two ways the Odoo report path fails silently rather than loudly.
 *
 * <p>The rendering itself needs a live Odoo (wkhtmltopdf, a web session, a real picking) and is
 * exercised by the Odoo integration tests. What is unit-testable here is exactly what a live test
 * would <em>not</em> catch quickly: a URL derived wrongly (so every call 404s against a host that is
 * up) and a non-PDF body accepted as a document.
 */
@ExtendWith(MockitoExtension.class)
class OdooReportServiceTest {

    @Mock OdooJsonRpcClient rpc;
    @Mock OdooVersionResolver versionResolver;

    @InjectMocks OdooReportService service;

    // ── Base URL derivation ──────────────────────────────────────────────────

    @Test
    @DisplayName("strips the /jsonrpc suffix — report routes hang off the site root")
    void stripsJsonRpcSuffix() {
        when(rpc.rpcUrl()).thenReturn("https://erp.example.com/jsonrpc");
        assertThat(service.baseUrl()).isEqualTo("https://erp.example.com");
    }

    @Test
    @DisplayName("strips the XML-RPC endpoints too")
    void stripsXmlRpcSuffixes() {
        when(rpc.rpcUrl()).thenReturn("https://erp.example.com/xmlrpc/2/object");
        assertThat(service.baseUrl()).isEqualTo("https://erp.example.com");
    }

    @Test
    @DisplayName("tolerates trailing slashes")
    void toleratesTrailingSlash() {
        when(rpc.rpcUrl()).thenReturn("https://erp.example.com/jsonrpc/");
        assertThat(service.baseUrl()).isEqualTo("https://erp.example.com");
    }

    @Test
    @DisplayName("leaves a bare root untouched")
    void leavesRootUntouched() {
        when(rpc.rpcUrl()).thenReturn("https://erp.example.com");
        assertThat(service.baseUrl()).isEqualTo("https://erp.example.com");
    }

    @Test
    @DisplayName("keeps a sub-path mount — Odoo behind a reverse proxy prefix")
    void keepsSubPathMount() {
        when(rpc.rpcUrl()).thenReturn("https://example.com/odoo/jsonrpc");
        assertThat(service.baseUrl()).isEqualTo("https://example.com/odoo");
    }

    @Test
    @DisplayName("returns null when no URL is configured")
    void nullWhenUnconfigured() {
        when(rpc.rpcUrl()).thenReturn("");
        assertThat(service.baseUrl()).isNull();
    }

    // ── PDF magic-number guard ───────────────────────────────────────────────

    @Test
    @DisplayName("accepts a real PDF")
    void acceptsPdf() {
        assertThat(OdooReportService.looksLikePdf("%PDF-1.7\n…".getBytes(StandardCharsets.US_ASCII))).isTrue();
    }

    @Test
    @DisplayName("rejects Odoo's login page — the documented 200-OK failure")
    void rejectsLoginPage() {
        // When the session is not accepted, /report/pdf answers 200 with the login HTML. Storing that
        // as a delivery note would hand a driver a web page shaped like a legal document.
        byte[] html = "<!DOCTYPE html><html><head><title>Odoo</title>".getBytes(StandardCharsets.UTF_8);
        assertThat(OdooReportService.looksLikePdf(html)).isFalse();
    }

    @Test
    @DisplayName("rejects empty and truncated bodies")
    void rejectsEmpty() {
        assertThat(OdooReportService.looksLikePdf(null)).isFalse();
        assertThat(OdooReportService.looksLikePdf(new byte[0])).isFalse();
        assertThat(OdooReportService.looksLikePdf("%PD".getBytes(StandardCharsets.US_ASCII))).isFalse();
    }
}
