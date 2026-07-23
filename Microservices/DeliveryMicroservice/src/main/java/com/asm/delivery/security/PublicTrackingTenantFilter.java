package com.asm.delivery.security;

import com.asm.delivery.config.PublicTenantResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Sets the {@link TenantContext} for public tracking requests ({@code /api/public/track/{deliveryId}...}),
 * which carry no {@code X-Company-Id} header. The tenant is resolved from the {@code deliveryId} in the path
 * via {@link PublicTenantResolver}, so the tracking + public-RMA queries route to the right tenant schema
 * instead of falling through to {@code public} (where they'd find nothing).
 *
 * <p>Runs after {@link TenantContextFilter} (which leaves the context unset for header-less public traffic),
 * and clears the context afterwards.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
@RequiredArgsConstructor
public class PublicTrackingTenantFilter implements Filter {

    private static final String PREFIX = "/api/v1/public/track/";

    private final PublicTenantResolver resolver;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        boolean set = false;

        String path = httpReq.getRequestURI();
        if (path != null && path.startsWith(PREFIX)) {
            String rest = path.substring(PREFIX.length());
            int slash = rest.indexOf('/');
            String idPart = slash >= 0 ? rest.substring(0, slash) : rest;
            try {
                UUID deliveryId = UUID.fromString(idPart);
                UUID companyId = resolver.resolveCompanyId(deliveryId);
                if (companyId != null) {
                    TenantContext.set(companyId);
                    set = true;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a UUID (or unknown delivery) — leave the context unset; the service returns 404.
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            if (set) TenantContext.clear();
        }
    }
}
