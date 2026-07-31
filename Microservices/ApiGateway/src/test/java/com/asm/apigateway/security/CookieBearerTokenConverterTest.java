package com.asm.apigateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the gateway looks for a token.
 *
 * <p>The admin SPA keeps its access token in an HttpOnly cookie — deliberately, so a cross-site
 * script cannot read it — while the mobile app sends an Authorization header. Both must
 * authenticate, and the precedence between them has to be stated rather than assumed: get it
 * backwards and a stale cookie quietly overrides the header the caller just sent.
 */
class CookieBearerTokenConverterTest {

    private final CookieBearerTokenConverter converter = new CookieBearerTokenConverter();

    /** The converter is reactive but does no I/O, so blocking here costs nothing and reads better. */
    private String tokenFrom(MockServerWebExchange exchange) {
        var auth = converter.convert(exchange).block();
        return auth == null ? null : ((BearerTokenAuthenticationToken) auth).getToken();
    }

    @Test
    void takesTheTokenFromTheAuthorizationHeader() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/admin/drivers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer header-token"));

        assertThat(tokenFrom(exchange)).isEqualTo("header-token");
    }

    @Test
    void fallsBackToTheAccessTokenCookie() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/admin/drivers")
                        .cookie(new org.springframework.http.HttpCookie("access_token", "cookie-token")));

        assertThat(tokenFrom(exchange)).isEqualTo("cookie-token");
    }

    @Test
    void prefersTheHeaderWhenBothArePresent() {
        // An explicit header is the caller's current intent; the cookie is ambient state that may
        // belong to a session they have already moved on from.
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/admin/drivers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer header-token")
                        .cookie(new org.springframework.http.HttpCookie("access_token", "cookie-token")));

        assertThat(tokenFrom(exchange)).isEqualTo("header-token");
    }

    @Test
    void producesNothingWhenThereIsNoToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/drivers"));
        assertThat(tokenFrom(exchange)).isNull();
    }

    @Test
    void ignoresAnEmptyCookie() {
        // Browsers keep sending `access_token=` after a logout that cleared the value. Treating it
        // as a token turns a clean "not authenticated" into a confusing "invalid token".
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/admin/drivers")
                        .cookie(new org.springframework.http.HttpCookie("access_token", "   ")));

        assertThat(tokenFrom(exchange)).isNull();
    }

    @Test
    void ignoresACookieUnderAnotherName() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/admin/drivers")
                        .cookie(new org.springframework.http.HttpCookie("refresh_token", "not-the-one")));

        assertThat(tokenFrom(exchange)).isNull();
    }
}
