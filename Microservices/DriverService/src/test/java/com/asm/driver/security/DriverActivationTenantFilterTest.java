package com.asm.driver.security;

import com.asm.driver.config.DriverActivationTenantResolver;
import com.asm.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L'épinglage du tenant pendant l'activation d'un compte chauffeur.
 *
 * <p>Ce que ces tests protègent : l'activation est nécessairement anonyme, le chauffeur n'ayant pas
 * encore de compte. Sans ce filtre, les requêtes s'exécutaient sur le schéma {@code public}, où
 * aucun jeton d'invitation ne vit, et tout chauffeur recevait « jeton invalide ou expiré » quelle
 * que soit l'entreprise. Le tenant est donc déduit de l'identifiant que la requête porte déjà.
 */
@ExtendWith(MockitoExtension.class)
class DriverActivationTenantFilterTest {

    private static final String SETUP = "/api/v1/auth/driver/setup";
    private static final UUID COMPANY = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TOKEN = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock
    private DriverActivationTenantResolver resolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DriverActivationTenantFilter filter() {
        return new DriverActivationTenantFilter(resolver, objectMapper);
    }

    /** Le contexte vit dans un ThreadLocal : un test qui fuit contaminerait le suivant. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Capture le tenant tel qu'il est vu *pendant* la chaîne, pas après. */
    private static FilterChain capturing(AtomicReference<UUID> seen) {
        return (ServletRequest req, ServletResponse res) -> seen.set(TenantContext.get());
    }

    @Test
    void leavesUnrelatedPathsUntouched() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/drivers/me");
        var chain = new MockFilterChain();

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(resolver);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void resolvesTheTenantFromTheInviteTokenPassedAsAQueryParameter() throws Exception {
        when(resolver.resolveByInviteToken(TOKEN)).thenReturn(COMPANY);
        var request = new MockHttpServletRequest("GET", SETUP);
        request.setParameter("token", TOKEN.toString());
        var seen = new AtomicReference<UUID>();

        filter().doFilter(request, new MockHttpServletResponse(), capturing(seen));

        assertThat(seen.get()).isEqualTo(COMPANY);
    }

    @Test
    void resolvesTheTenantFromTheInviteTokenCarriedInTheJsonBody() throws Exception {
        when(resolver.resolveByInviteToken(TOKEN)).thenReturn(COMPANY);
        var request = new MockHttpServletRequest("POST", SETUP);
        request.setContent(("{\"token\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8));
        var seen = new AtomicReference<UUID>();

        filter().doFilter(request, new MockHttpServletResponse(), capturing(seen));

        assertThat(seen.get()).isEqualTo(COMPANY);
    }

    /** Un renvoi d'invitation ne porte pas de jeton : le tenant vient alors du téléphone. */
    @Test
    void fallsBackOnThePhoneNumberWhenNoTokenIsPresent() throws Exception {
        when(resolver.resolveByPhone("+21620000001")).thenReturn(COMPANY);
        var request = new MockHttpServletRequest("POST", SETUP + "/resend");
        request.setContent("{\"phone\":\"  +21620000001  \"}".getBytes(StandardCharsets.UTF_8));
        var seen = new AtomicReference<UUID>();

        filter().doFilter(request, new MockHttpServletResponse(), capturing(seen));

        assertThat(seen.get()).isEqualTo(COMPANY);
    }

    @Test
    void doesNotConsultTheResolverWhenTheTokenIsNotAUuid() throws Exception {
        var request = new MockHttpServletRequest("GET", SETUP);
        request.setParameter("token", "pas-un-uuid");
        var seen = new AtomicReference<UUID>();

        filter().doFilter(request, new MockHttpServletResponse(), capturing(seen));

        verify(resolver, never()).resolveByInviteToken(any());
        assertThat(seen.get()).isNull();
    }

    /** Un jeton inconnu ne doit pas interrompre la requête : le contrôleur répondra lui-même. */
    @Test
    void letsTheRequestThroughWhenNoTenantOwnsTheToken() throws Exception {
        when(resolver.resolveByInviteToken(TOKEN)).thenReturn(null);
        var request = new MockHttpServletRequest("GET", SETUP);
        request.setParameter("token", TOKEN.toString());
        var chain = new MockFilterChain();

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(TenantContext.get()).isNull();
    }

    /**
     * Le corps est lu par le filtre avant le contrôleur. S'il n'était pas remis en mémoire tampon,
     * le contrôleur recevrait un flux déjà consommé.
     */
    @Test
    void leavesTheRequestBodyReadableForTheController() throws Exception {
        when(resolver.resolveByInviteToken(TOKEN)).thenReturn(COMPANY);
        String body = "{\"token\":\"" + TOKEN + "\",\"password\":\"secret\"}";
        var request = new MockHttpServletRequest("POST", SETUP);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        var relu = new AtomicReference<String>();

        filter().doFilter(request, new MockHttpServletResponse(),
                (ServletRequest req, ServletResponse res) ->
                        relu.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(relu.get()).isEqualTo(body);
    }

    /** Le fil est recyclé entre deux requêtes : rien ne doit survivre à la chaîne. */
    @Test
    void clearsTheTenantOnceTheChainHasRun() throws Exception {
        when(resolver.resolveByInviteToken(TOKEN)).thenReturn(COMPANY);
        var request = new MockHttpServletRequest("GET", SETUP);
        request.setParameter("token", TOKEN.toString());

        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(TenantContext.get()).isNull();
    }
}
