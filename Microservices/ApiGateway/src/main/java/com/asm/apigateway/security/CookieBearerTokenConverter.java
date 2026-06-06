package com.asm.apigateway.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Resolves bearer token from Authorization header first, then falls back
 * to the HttpOnly access_token cookie used by the admin React SPA.
 */
public class CookieBearerTokenConverter implements ServerAuthenticationConverter {

    private final ServerBearerTokenAuthenticationConverter headerConverter =
            new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return headerConverter.convert(exchange)
                .switchIfEmpty(Mono.defer(() -> {
                    var cookie = exchange.getRequest().getCookies().getFirst("access_token");
                    if (cookie != null && !cookie.getValue().isBlank()) {
                        return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
                    }
                    return Mono.empty();
                }));
    }
}
