package com.asm.driver.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * L'origine sur laquelle une URL de média est construite.
 *
 * <p>Ce que ces tests protègent : une adresse d'avatar assemblée sur un hôte figé au démarrage
 * pointait, dès que le serveur changeait d'adresse, vers une machine que l'appelant ne pouvait pas
 * joindre. Un chauffeur en partage de connexion atteignait l'API et voyait une image cassée. La
 * résolution se fait donc depuis l'appelant, selon un ordre précis que ces cas verrouillent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicOriginResolverTest {

    @Mock
    private MinioConfig minioConfig;

    private PublicOriginResolver resolver(String configuredBaseUrl) {
        var r = new PublicOriginResolver(minioConfig);
        ReflectionTestUtils.setField(r, "configuredBaseUrl", configuredBaseUrl);
        return r;
    }

    /** Chaque test qui pose une requête doit la retirer : le porteur est un ThreadLocal. */
    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void currentRequestIs(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // ── 1. La configuration explicite l'emporte ─────────────────────────────

    @Test
    void prefersTheConfiguredBaseUrlOverEverythingElse() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "ignore.example");
        currentRequestIs(request);

        assertThat(resolver("https://cdn.asm-track.tn").origin())
                .isEqualTo("https://cdn.asm-track.tn");
    }

    @Test
    void trimsTheTrailingSlashOfTheConfiguredBaseUrl() {
        assertThat(resolver("https://cdn.asm-track.tn/").origin())
                .isEqualTo("https://cdn.asm-track.tn");
    }

    // ── 2. Sinon, l'origine de l'appelant ───────────────────────────────────

    @Test
    void usesTheForwardedHostAndProtocolWhenTheGatewayProvidesThem() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "asm-track.asmtechtn.com");
        request.addHeader("X-Forwarded-Proto", "https");
        currentRequestIs(request);

        assertThat(resolver("").origin()).isEqualTo("https://asm-track.asmtechtn.com");
    }

    @Test
    void fallsBackOnTheRequestSchemeWhenOnlyTheHostIsForwarded() {
        var request = new MockHttpServletRequest();
        request.setScheme("http");
        request.addHeader("X-Forwarded-Host", "10.157.92.125");
        currentRequestIs(request);

        assertThat(resolver("").origin()).isEqualTo("http://10.157.92.125");
    }

    /** Un en-tête transféré peut porter une chaîne de mandataires ; le premier est le client. */
    @Test
    void keepsOnlyTheFirstEntryOfAForwardedChain() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "client.example, proxy1.example, proxy2.example");
        request.addHeader("X-Forwarded-Proto", "https, http");
        currentRequestIs(request);

        assertThat(resolver("").origin()).isEqualTo("https://client.example");
    }

    @Test
    void usesTheRequestOwnSchemeAndHostWhenNothingIsForwarded() {
        var request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(80);
        currentRequestIs(request);

        assertThat(resolver("").origin()).isEqualTo("http://localhost");
    }

    @Test
    void omitsThePortWhenItIsTheDefaultForTheScheme() {
        var request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("asm-track.asmtechtn.com");
        request.setServerPort(443);
        currentRequestIs(request);

        assertThat(resolver("").origin()).isEqualTo("https://asm-track.asmtechtn.com");
    }

    @Test
    void keepsThePortWhenItIsNotTheDefaultOne() {
        var request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(5173);
        currentRequestIs(request);

        assertThat(resolver("").origin()).isEqualTo("http://localhost:5173");
    }

    // ── 3. Sans requête du tout : appelants serveur ─────────────────────────

    @Test
    void usesTheConfiguredMinioPublicUrlWhenThereIsNoRequest() {
        when(minioConfig.getPublicUrl()).thenReturn("http://minio.internal:9000");

        assertThat(resolver("").origin()).isEqualTo("http://minio.internal:9000");
    }

    /**
     * La valeur configurée portait historiquement le préfixe de la passerelle, que les appelants
     * ajoutent désormais eux-mêmes. Sans ce retrait, il apparaîtrait deux fois.
     */
    @Test
    void stripsTheGatewayFilesPrefixFromTheConfiguredUrl() {
        when(minioConfig.getPublicUrl()).thenReturn("http://192.168.10.76/files");

        assertThat(resolver("").origin()).isEqualTo("http://192.168.10.76");
    }

    @Test
    void fallsBackOnTheInternalMinioUrlWhenNoPublicOneIsConfigured() {
        when(minioConfig.getPublicUrl()).thenReturn("");
        when(minioConfig.getUrl()).thenReturn("http://minio:9000/");

        assertThat(resolver("").origin()).isEqualTo("http://minio:9000");
    }
}
