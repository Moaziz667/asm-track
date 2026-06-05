package com.asm.delivery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    // Tenant filtering is handled by TenantFilterAspect (AOP, inside @Transactional).
    // Outbound HTTP now uses Feign clients (service-to-service) and RestClient (external);
    // no shared RestTemplate bean is needed.
}
