package com.asm.erpadapter.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Web filter that reads the X-Company-Id header (injected by the API Gateway)
 * and stores it in the {@link TenantContext} ThreadLocal for the duration of the request.
 * Enforces that every authenticated request carries a valid company identifier.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String companyIdHeader = httpReq.getHeader("X-Company-Id");

        // Fail-closed: NEVER default to a catch-all company (silent cross-tenant leak). The Gateway is
        // the enforcement point — an authenticated request always carries a valid X-Company-Id or is
        // rejected upstream (403). A missing/blank header here is therefore tenant-less traffic
        // (public tracking, actuator): leave the context UNSET so the DB layer resolves no tenant
        // rather than the wrong one. An invalid header is a bug/attack → reject.
        UUID companyId = null;
        if (companyIdHeader != null && !companyIdHeader.isBlank()) {
            try {
                companyId = UUID.fromString(companyIdHeader);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid X-Company-Id format '{}' — rejecting", companyIdHeader);
                httpResp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                httpResp.setContentType("application/json");
                httpResp.getWriter().write("{\"status\":400,\"message\":\"Invalid tenant identifier\"}");
                return;
            }
        }

        // Fail-closed: every /api/** route on this service resolves ERP settings/credentials by
        // tenant. Without a tenant the settings silently degrade to NONE — reject instead so a
        // caller that lost the header fails loudly rather than "syncing" against nothing.
        if (companyId == null && httpReq.getRequestURI() != null && httpReq.getRequestURI().startsWith("/api/")) {
            log.warn("Missing X-Company-Id on tenant-scoped path {} — rejecting (fail-closed)", httpReq.getRequestURI());
            httpResp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResp.setContentType("application/json");
            httpResp.getWriter().write("{\"status\":403,\"message\":\"No tenant context\"}");
            return;
        }

        if (companyId != null) {
            TenantContext.set(companyId);
        }
        // MDC: every log line of this request is attributable to a tenant/user/request without
        // touching individual log statements — the missing piece that made the multi-tenant
        // regressions so hard to localize.
        org.slf4j.MDC.put("companyId", companyId != null ? companyId.toString() : "-");
        String userId = httpReq.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) org.slf4j.MDC.put("userId", userId);
        String requestId = httpReq.getHeader("X-Request-Id");
        org.slf4j.MDC.put("requestId",
                requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            org.slf4j.MDC.remove("companyId");
            org.slf4j.MDC.remove("userId");
            org.slf4j.MDC.remove("requestId");
        }
    }
}
