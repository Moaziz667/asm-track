package com.asm.delivery.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Delivery Service API")
                        .description("""
                            ASM Delivery Platform — Delivery Service

                            ## Contracts frontend disponibles
                            - `admin`: endpoints du back-office (`/api/admin/**`, `/api/v1/**`)
                            - `driver`: endpoints app livreur (`/api/driver/**`)
                            - `client`: endpoints app client (`/api/orders/**`, `/api/deliveries/**`)
                            - `dev`: endpoints utilitaires (`/api/dev/**`)

                            ## URLs utiles
                            - Swagger UI: `/swagger-ui.html`
                            - OpenAPI global: `/v3/api-docs`
                            - OpenAPI par groupe: `/v3/api-docs/{group}`

                            ## Authentification
                            1. Générer un token de test via `POST /api/dev/client-token`.
                            2. Cliquer sur **Authorize** et coller le JWT (sans préfixe `Bearer `).
                            """)
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    public GroupedOpenApi adminApi(OpenApiCustomizer defaultResponsesCustomizer) {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/api/admin/**", "/api/v1/**")
                .addOpenApiCustomizer(defaultResponsesCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi driverApi(OpenApiCustomizer defaultResponsesCustomizer) {
        return GroupedOpenApi.builder()
                .group("driver")
                .pathsToMatch("/api/driver/**")
                .addOpenApiCustomizer(defaultResponsesCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi clientApi(OpenApiCustomizer defaultResponsesCustomizer) {
        return GroupedOpenApi.builder()
                .group("client")
                .pathsToMatch("/api/orders/**", "/api/deliveries/**")
                .addOpenApiCustomizer(defaultResponsesCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi devApi(OpenApiCustomizer defaultResponsesCustomizer) {
        return GroupedOpenApi.builder()
                .group("dev")
                .pathsToMatch("/api/dev/**")
                .addOpenApiCustomizer(defaultResponsesCustomizer)
                .build();
    }

    @Bean
    public OpenApiCustomizer defaultResponsesCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            openApi.getPaths().values().forEach(pathItem ->
                    pathItem.readOperations().forEach(operation -> {
                        if (operation.getResponses() == null) {
                            return;
                        }
                        operation.getResponses().putIfAbsent("400", new ApiResponse().description("Requete invalide"));
                        operation.getResponses().putIfAbsent("401", new ApiResponse().description("Authentification requise"));
                        operation.getResponses().putIfAbsent("403", new ApiResponse().description("Acces refuse"));
                        operation.getResponses().putIfAbsent("500", new ApiResponse().description("Erreur interne serveur"));
                    })
            );
        };
    }
}
