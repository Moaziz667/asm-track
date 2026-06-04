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
                        .title("ASM Track — Delivery Service API")
                        .description("""
                            ## ASM Track · Delivery Microservice

                            Core service for the ASM Track last-mile delivery platform.
                            Handles deliveries, routes, ERP integration, driver coordination, and real-time operations.

                            ---

                            ## API Groups

                            | Group | Prefix | Used by |
                            |-------|--------|---------|
                            | **admin** | `/api/admin/**`, `/api/v1/**` | Admin web app |
                            | **driver** | `/api/driver/**` | Driver mobile app |
                            | **client** | `/api/orders/**` | Client mobile app (legacy) |
                            | **public** | `/api/public/**` | Anyone — tracking link |

                            Use the **group selector** (top right) to filter endpoints by consumer.

                            ---

                            ## Authentication

                            All protected endpoints require a Bearer JWT in the `Authorization` header.
                            Admin and driver tokens are issued by separate services and cannot be mixed.

                            **To test in Swagger UI:**
                            1. Get a token via `POST /api/auth/admin/login` (AppBackend on port 8080)
                            2. Click **Authorize** (lock icon) and paste the token — no `Bearer` prefix needed

                            ---

                            ## Key Flows

                            **ERP Import:** `/api/admin/erp/pending-orders` → `/api/admin/erp/import-order/{id}`

                            **Delivery lifecycle:** `UNSCHEDULED → SCHEDULED → PICKED_UP → IN_TRANSIT → DELIVERED`

                            **Route management:** Create → Add stops → Optimize → Validate → Driver starts

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
