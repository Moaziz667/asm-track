package com.asm.appbackend.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les règles d'autorisation, figées.
 *
 * <p>Se tromper ici n'est pas une anomalie mais un incident : une règle qui capte une route trop tôt
 * ouvre un point d'entrée à un rôle qui ne devait pas l'atteindre. Le fichier de politique est
 * recopié verbatim depuis celui de la passerelle, et un contrôle de la chaîne d'intégration refuse
 * toute divergence. Le risque n'est donc pas une modification de cette classe, mais une édition
 * bien intentionnée du fichier partagé qui réordonnerait les règles.
 */
class RbacPolicyTest {

    private static final Set<String> PERSONNE = Set.of();

    private static boolean autorise(String path, Set<String> roles) {
        return RbacPolicy.isAuthorized(path, roles, HttpMethod.GET);
    }

    /**
     * La politique doit refuser par défaut. Une route livrée sans règle doit être injoignable
     * plutôt qu'ouverte : un 403 remonte, un trou silencieux non.
     */
    @Test
    void deniesAPathNoRuleCovers() {
        assertThat(autorise("/api/v1/rien-de-declare", PERSONNE)).isFalse();
        assertThat(autorise("/api/v1/rien-de-declare", Set.of("ADMIN", "perm:settings:manage"))).isFalse();
    }

    @Test
    void deniesEveryCallerWithoutASingleRole() {
        assertThat(autorise("/api/v1/orders/42", PERSONNE)).isFalse();
        assertThat(autorise("/api/assistant/ask", PERSONNE)).isFalse();
    }

    @Test
    void reservesTheClientApiToTheClientRole() {
        assertThat(autorise("/api/v1/orders/42", Set.of("CLIENT"))).isTrue();
        assertThat(autorise("/api/v1/orders/42", Set.of("DRIVER"))).isFalse();
    }

    /**
     * L'application chauffeur et le back-office s'authentifient sur le même royaume. Seul le rôle
     * les sépare, d'où cette vérification croisée.
     */
    @Test
    void keepsDriversAndBackOfficeApart() {
        assertThat(autorise("/api/v1/driver/route", Set.of("DRIVER"))).isTrue();
        assertThat(autorise("/api/v1/driver/route", Set.of("ADMIN"))).isFalse();
    }

    @Test
    void opensTheAssistantToTheThreeBackOfficeRoles() {
        assertThat(autorise("/api/assistant/ask", Set.of("ADMIN"))).isTrue();
        assertThat(autorise("/api/assistant/ask", Set.of("DISPATCHER"))).isTrue();
        assertThat(autorise("/api/assistant/ask", Set.of("MANAGER"))).isTrue();
        assertThat(autorise("/api/assistant/ask", Set.of("DRIVER"))).isFalse();
    }

    /**
     * Le cas qui justifie cette classe. La règle de l'administration de l'assistant est déclarée
     * AVANT la règle générale, et exige une permission que le simple accès à l'assistant n'implique
     * pas. Réordonner les deux donnerait à tout responsable la configuration du service.
     */
    @Test
    void doesNotLetAssistantAccessImplyAssistantAdministration() {
        assertThat(autorise("/api/assistant/admin", Set.of("MANAGER"))).isFalse();
        assertThat(autorise("/api/assistant/admin", Set.of("DISPATCHER"))).isFalse();
        assertThat(autorise("/api/assistant/admin", Set.of("ADMIN", "perm:settings:manage"))).isTrue();
    }

    @Test
    void reservesTheAuditTrailToItsOwnPermission() {
        assertThat(autorise("/api/assistant/audit", Set.of("MANAGER", "perm:audit:view"))).isTrue();
        assertThat(autorise("/api/assistant/audit", Set.of("DISPATCHER"))).isFalse();
    }
}
