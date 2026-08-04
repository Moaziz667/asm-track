package com.asm.tenant.web;

import com.asm.tenant.TenantContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Reads the {@code X-Company-Id} header injected by the API Gateway and holds it in
 * {@link TenantContext} for the duration of the request.
 *
 * <h2>The one thing that varies between services</h2>
 * Which paths may legitimately arrive without a tenant. Every service needs the same rule — a
 * business route without a tenant is rejected — but not the same exceptions: the delivery service
 * serves public tracking, the others do not. That list used to be hard-coded, which meant four
 * copies of this class existed only so that four different {@code startsWith} chains could live in
 * them. It is now a constructor argument, so the behaviour is one implementation and the difference
 * is configuration.
 *
 * <p>Pass an empty list for a service where every {@code /api/} route is tenant-scoped.
 */
@Slf4j
public class TenantContextFilter implements Filter {

    private final List<String> tenantLessPrefixes;

    public TenantContextFilter(List<String> tenantLessPrefixes) {
        this.tenantLessPrefixes = List.copyOf(tenantLessPrefixes);
    }

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

        // Fail-closed at the DB boundary too: an authenticated business route without a tenant would
        // otherwise silently read/write the (empty) `public` schema — bugs become invisible empty
        // results instead of loud failures.
        if (companyId == null && requiresTenant(httpReq.getRequestURI())) {
            log.warn("Missing X-Company-Id on tenant-scoped path {} — rejecting (fail-closed)",
                    httpReq.getRequestURI());
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

    /** Business API paths need a tenant; the configured prefixes do not (actuator, /ws, /internal, swagger never do). */
    boolean requiresTenant(String path) {
        if (path == null || !path.startsWith("/api/")) return false;
        return tenantLessPrefixes.stream().noneMatch(path::startsWith);
    }
}
