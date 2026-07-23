package com.asm.erpadapter.config;

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
 * Spring Security's {@link OAuth2AuthorizedClientManager} acquires/caches/refreshes the
 * {@code client_credentials} token; the interceptor stamps it on every Feign call and forwards
 * the original caller identity (F1b) for downstream audit attribution.
 */
@Configuration
@EnableFeignClients(basePackages = "com.asm.erpadapter")
@Slf4j
public class ServiceClientConfig {

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
                log.error("Service token unavailable for Feign call (registration={})", REGISTRATION_ID);
            }
        } catch (Exception e) {
            log.error("Failed to acquire service token for Feign call: {}", e.getMessage());
        }
    }

    private void forwardActorOrMarkService(RequestTemplate template) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            java.util.UUID tenantCompanyId = com.asm.erpadapter.security.TenantContext.get();
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
            java.util.UUID tenantCompanyId = com.asm.erpadapter.security.TenantContext.get();
            if (tenantCompanyId != null) {
                template.header("X-Company-Id", tenantCompanyId.toString());
            }
            template.header("X-Actor", "SERVICE");
        }
    }
}
