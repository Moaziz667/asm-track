package com.asm.delivery.config;

import com.asm.tenant.TenantContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Single source of service-to-service HTTP auth for all Feign clients in this service.
 *
 * <p>Replaces the hand-rolled {@code client_credentials} token fetch/cache that used to be
 * duplicated across adapters. Spring Security's {@link OAuth2AuthorizedClientManager} acquires,
 * caches and refreshes the token; the Feign {@link RequestInterceptor} stamps it on every call.
 *
 * <p>Caller identity (F1b): when the outbound call is triggered by an inbound user request, the
 * gateway-injected {@code X-User-*} headers are forwarded so downstream audit attributes the real
 * actor rather than "SERVICE". For system/scheduled calls (no request context) an {@code X-Actor:
 * SERVICE} marker is sent instead.
 */
@Configuration
@EnableFeignClients(basePackages = "com.asm.delivery")
@Slf4j
public class ServiceClientConfig {

    /** Matches spring.security.oauth2.client.registration.asm-svc in application.yml. */
    public static final String REGISTRATION_ID = "asm-svc";

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientService authorizedClients) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    public RequestInterceptor serviceAuthRequestInterceptor(OAuth2AuthorizedClientManager manager) {
        return template -> {
            applyServiceToken(template, manager);
            forwardActorOrMarkService(template);
        };
    }

    private void applyServiceToken(RequestTemplate template, OAuth2AuthorizedClientManager manager) {
        try {
            OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                    .withClientRegistrationId(REGISTRATION_ID)
                    .principal(REGISTRATION_ID)
                    .build();
            OAuth2AuthorizedClient client = manager.authorize(request);
            if (client != null && client.getAccessToken() != null) {
                template.header("Authorization", "Bearer " + client.getAccessToken().getTokenValue());
            } else {
                log.error("Service token unavailable for Feign call to {} (registration={})",
                        template.feignTarget() != null ? template.feignTarget().name() : "?", REGISTRATION_ID);
            }
        } catch (Exception e) {
            log.error("Failed to acquire service token for Feign call: {}", e.getMessage());
        }
    }

    private void forwardActorOrMarkService(RequestTemplate template) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // Scheduled job / non-HTTP context: forward X-Company-Id from TenantContext
            java.util.UUID tenantCompanyId = TenantContext.get();
            if (tenantCompanyId != null) {
                template.header("X-Company-Id", tenantCompanyId.toString());
            }
            template.header("X-Actor", "SERVICE");
            return;
        }
        boolean forwardedAny = false;
        for (String header : new String[]{"X-User-Id", "X-User-Role", "X-User-Name", "X-Company-Id"}) {
            String value = attrs.getRequest().getHeader(header);
            if (value != null && !value.isBlank()) {
                template.removeHeader(header);
                template.header(header, value);
                forwardedAny = true;
            }
        }
        if (!forwardedAny) {
            // Fallback: try TenantContext if no header was forwarded
            java.util.UUID tenantCompanyId = TenantContext.get();
            if (tenantCompanyId != null) {
                template.header("X-Company-Id", tenantCompanyId.toString());
            }
            template.header("X-Actor", "SERVICE");
        }
    }
}
