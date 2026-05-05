package com.asm.erpadapter.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Configuration
public class SecurityConfig {

    @Value("${internal.secret:asm-internal-2026}")
    private String internalSecret;

    @Bean
    public OncePerRequestFilter internalSecretFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
                String path = request.getRequestURI();
                if (path.startsWith("/api/")) {
                    String secret = request.getHeader("X-Internal-Secret");
                    if (secret == null || !secret.equals(internalSecret)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Internal Secret");
                        return;
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }
}
