package com.asm.delivery.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        final String schemeName = "Bearer Authentication";
        return new OpenAPI()
                .info(new Info()
                        .title("Delivery Service API")
                        .description("""
                            ASM Delivery Platform — Delivery Service
                            
                            **How to test:**
                            1. Use `POST /api/dev/client-token` to get a test CLIENT JWT.
                            2. Use `POST /api/auth/driver/register` then `POST /api/auth/driver/login` for a DRIVER JWT.
                            3. Click **Authorize** and paste the token (without "Bearer ").
                            """)
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(schemeName))
                .components(new Components()
                        .addSecuritySchemes(schemeName, new SecurityScheme()
                                .name(schemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
