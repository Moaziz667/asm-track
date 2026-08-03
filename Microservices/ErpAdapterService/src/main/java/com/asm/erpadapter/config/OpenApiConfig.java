package com.asm.erpadapter.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How this service's API describes itself.
 *
 * <p>Every endpoint was already annotated — 26 of them, each with an {@code @Operation} — but the
 * document wrapping them announced itself as springdoc's default "OpenAPI definition, v0". The
 * operations said what they did; nothing said what the service was, which is the one thing a reader
 * opening the page does not already know.
 *
 * <p>The security scheme is the part worth stating rather than copying. Unlike the other services,
 * nothing here is reachable with a user's token: {@code SecurityConfig} guards {@code /api/**} with
 * {@code hasRole("SERVICE")}, so callers are the other microservices holding a client-credentials
 * token, never a browser and never the driver app. Declaring a plain "bearer JWT" like the
 * user-facing services do would be true of the transport and misleading about who may call.
 */
@Configuration
public class OpenApiConfig {

    private static final String SERVICE_TOKEN = "Service Token";

    @Bean
    public OpenAPI erpAdapterOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ASM ERP Adapter Service API")
                        .version("v1.0.0")
                        .description("""
                                Internal service. Translates ASM's delivery lifecycle into the ERP a \
                                given tenant runs — Odoo (16 → 19) or ERPNext — and back.

                                Not exposed through the API gateway: it has no route there, and its \
                                contract is deliberately absent from docs/openapi/. Reachable only \
                                from inside the stack, by another service.

                                Every /api/** endpoint requires the SERVICE role. A user token, \
                                however valid, is rejected."""))
                .addSecurityItem(new SecurityRequirement().addList(SERVICE_TOKEN))
                .components(new Components()
                        .addSecuritySchemes(SERVICE_TOKEN, new SecurityScheme()
                                .name(SERVICE_TOKEN)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Keycloak client-credentials token carrying the SERVICE "
                                        + "role. Issued to a service, not to a person.")));
    }
}
