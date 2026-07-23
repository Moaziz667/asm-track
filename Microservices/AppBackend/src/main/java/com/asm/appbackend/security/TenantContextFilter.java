package com.asm.appbackend.security;

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

        if (companyId != null) {
            TenantContext.set(companyId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
