package com.asm.apigateway.config;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gives gateway errors the same shape as the services behind it.
 *
 * <p>Spring's default answer carries {@code timestamp / path / status / error / requestId}, while
 * every microservice answers {@code status / message / errorCode / errorParams / timestamp}. A
 * client therefore had to parse two formats and could not rely on {@code errorCode} at all: the
 * gateway produces its own replies for a path that matches no route, for an unreachable service, and
 * for a refused authorisation — none of which ever reach a controller.
 *
 * <p>The {@code errorCode} is derived from the status rather than invented, so the vocabulary stays
 * the one already used downstream.
 */
@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        Map<String, Object> defaults = super.getErrorAttributes(request, options);

        int status = defaults.get("status") instanceof Integer s ? s : 500;
        Object message = defaults.get("message");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", messageFor(status, message));
        body.put("errorCode", codeFor(status));
        body.put("errorParams", Map.of("path", String.valueOf(defaults.getOrDefault("path", ""))));
        body.put("timestamp", LocalDateTime.now().toString());
        return body;
    }

    /**
     * A 5xx message is never forwarded as-is: the underlying text names classes and hosts, which
     * tells a caller more about the deployment than about his request.
     */
    private String messageFor(int status, Object original) {
        if (status >= 500) {
            return status == HttpStatus.SERVICE_UNAVAILABLE.value()
                    ? "Service temporairement indisponible"
                    : "Erreur interne";
        }
        String text = original == null ? "" : String.valueOf(original).trim();
        if (!text.isEmpty()) return text;
        return switch (status) {
            case 401 -> "Authentification requise";
            case 403 -> "Accès refusé";
            case 404 -> "Aucun endpoint pour ce chemin";
            default -> "Requête invalide";
        };
    }

    private String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "ENDPOINT_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_SERVER_ERROR" : "REQUEST_REJECTED";
        };
    }
}
