package com.asm.driver.security;

import com.asm.tenant.TenantContext;
import com.asm.tenant.web.TenantContextFilter;

import com.asm.driver.config.DriverActivationTenantResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pins the {@link TenantContext} for driver account activation, which is necessarily
 * <b>unauthenticated</b>: the driver has no account yet, so the request carries no token and the
 * gateway injects no {@code X-Company-Id}.
 *
 * <p>Without this, activation queries ran against the {@code public} schema — where invite tokens
 * never live — so every attempt returned "Invalid or expired invite token" and no driver could
 * activate in any tenant. The tenant is instead derived from the identifier the request already
 * carries: the invite token (query param or JSON body) or, for a resend, the phone number.
 *
 * <p>Runs after {@link TenantContextFilter} (which leaves the context unset for this public path)
 * and clears whatever it set afterwards, so nothing leaks onto the pooled request thread.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
@RequiredArgsConstructor
public class DriverActivationTenantFilter implements Filter {

    private static final String PREFIX = "/api/v1/auth/driver/setup";

    private final DriverActivationTenantResolver resolver;
    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        String path = httpReq.getRequestURI();
        if (path == null || !path.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // The POST body must stay readable by the controller, so cache it before parsing.
        CachedBodyRequest cached = new CachedBodyRequest(httpReq);
        UUID companyId = resolveTenant(cached);
        boolean set = false;
        if (companyId != null) {
            TenantContext.set(companyId);
            set = true;
        } else {
            log.warn("Driver activation: no tenant owns this invite token/phone — path={}", path);
        }

        try {
            chain.doFilter(cached, response);
        } finally {
            if (set) TenantContext.clear();
        }
    }

    private UUID resolveTenant(CachedBodyRequest req) {
        String token = req.getParameter("token");
        if (token == null) token = readJsonField(req, "token");
        if (token != null) {
            try {
                return resolver.resolveByInviteToken(UUID.fromString(token.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Driver activation: malformed invite token '{}'", token);
                return null;
            }
        }
        String phone = req.getParameter("phone");
        if (phone == null) phone = readJsonField(req, "phone");
        return phone != null ? resolver.resolveByPhone(phone.trim()) : null;
    }

    private String readJsonField(CachedBodyRequest req, String field) {
        byte[] body = req.body();
        if (body.length == 0) return null;
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode value = node.get(field);
            return value != null && !value.isNull() ? value.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Buffers the body so both this filter and the controller can read it. */
    private static class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            try (InputStream is = request.getInputStream()) {
                this.body = is != null ? is.readAllBytes() : new byte[0];
            }
        }

        byte[] body() { return body; }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return buffer.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { }
                @Override public int read() { return buffer.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
