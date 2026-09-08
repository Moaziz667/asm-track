package com.asm.delivery.service.analytics.filter;

import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.OrderSource;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Le filtre de périmètre partagé par toutes les requêtes analytiques.
 *
 * <p>Ce que ces tests protègent : chaque champ absent ne doit produire <b>ni clause ni paramètre</b>.
 * Une clause émise sans son paramètre lié fait échouer la requête au moment de l'exécution, loin de
 * l'endroit où le filtre a été construit, et un paramètre lié sans clause échoue tout autant. Les
 * deux moitiés doivent donc rester d'accord champ par champ.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsFilterTest {

    @Mock
    private Query query;

    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID ZONE = UUID.randomUUID();
    private static final UUID DEPOT = UUID.randomUUID();

    private static AnalyticsFilter withDrivers(UUID... ids) {
        return new AnalyticsFilter(List.of(ids), null, null, null, null, null, null);
    }

    // ── Le filtre vide est un vrai no-op ────────────────────────────────────

    @Test
    void producesNoClauseAtAllWhenNothingIsFiltered() {
        assertThat(AnalyticsFilter.NONE.isEmpty()).isTrue();
        assertThat(AnalyticsFilter.NONE.jpql()).isEmpty();
        assertThat(AnalyticsFilter.NONE.nativeSql()).isEmpty();
    }

    @Test
    void bindsNothingWhenNothingIsFiltered() {
        AnalyticsFilter.NONE.bind(query);
        AnalyticsFilter.NONE.bindNative(query);

        verifyNoInteractions(query);
    }

    /** Une liste vide doit se comporter exactement comme une liste absente. */
    @Test
    void treatsAnEmptyListLikeAnAbsentOne() {
        var filtre = new AnalyticsFilter(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());

        assertThat(filtre.isEmpty()).isTrue();
        assertThat(filtre.jpql()).isEmpty();
    }

    // ── Chaque pivot produit sa clause ──────────────────────────────────────

    @Test
    void narrowsOnDrivers() {
        var filtre = withDrivers(DRIVER);

        assertThat(filtre.isEmpty()).isFalse();
        assertThat(filtre.jpql()).contains("d.driverId IN :fDriverIds");
        assertThat(filtre.nativeSql()).contains("d.driver_id IN (:fDriverIds)");
    }

    @Test
    void narrowsOnZonesThroughTheOrderPath() {
        var filtre = new AnalyticsFilter(null, List.of(ZONE), null, null, null, null, null);

        assertThat(filtre.jpql()).contains("d.order.zoneId IN :fZoneIds");
        assertThat(filtre.nativeSql()).contains("o.zone_id IN (:fZoneIds)");
    }

    @Test
    void narrowsOnStatusesAndSources() {
        var filtre = new AnalyticsFilter(null, null, List.of(DeliveryStatus.DELIVERED), null,
                null, List.of(OrderSource.ODOO), null);

        assertThat(filtre.jpql()).contains("d.status IN :fStatuses").contains("d.order.source IN :fSources");
    }

    @Test
    void narrowsOnCities() {
        var filtre = new AnalyticsFilter(null, null, null, null, List.of("Sfax"), null, null);

        assertThat(filtre.jpql()).contains("d.order.dropoffCity IN :fCities");
    }

    /** Le dépôt n'est pas porté par la livraison : il faut passer par les arrêts de la tournée. */
    @Test
    void narrowsOnDepotsThroughAnExistsOverTheRouteStops() {
        var filtre = new AnalyticsFilter(null, null, null, null, null, null, List.of(DEPOT));

        assertThat(filtre.jpql()).contains("EXISTS").contains("RouteStop");
        assertThat(filtre.nativeSql()).contains("EXISTS").contains("route_stops");
    }

    @Test
    void combinesEveryPivotWithAnd() {
        var filtre = new AnalyticsFilter(List.of(DRIVER), List.of(ZONE),
                List.of(DeliveryStatus.DELIVERED), List.of("REFUSED"),
                List.of("Sfax"), List.of(OrderSource.ODOO), List.of(DEPOT));

        assertThat(filtre.jpql().split(" AND ")).hasSizeGreaterThanOrEqualTo(7);
    }

    // ── Les motifs non résolvables sont ignorés, pas rejetés ────────────────

    @Test
    void keepsOnlyTheMotifsThatMapToTheCoarseFailureCode() {
        var filtre = new AnalyticsFilter(null, null, null,
                List.of("refused", "  CLIENT_ABSENT  "), null, null, null);

        assertThat(filtre.isEmpty()).isFalse();
        assertThat(filtre.jpql()).contains("d.failureCode IN :fMotifs");
    }

    /**
     * Un code de catalogue granulaire n'existe pas sur {@code d.failureCode}. Le filtre le laisse
     * tomber au lieu de faire échouer la requête : une liste entièrement inconnue redevient un no-op.
     */
    @Test
    void dropsMotifsThatAreNotQueryableRatherThanFailing() {
        var filtre = new AnalyticsFilter(null, null, null,
                List.of("CODE_CATALOGUE_FIN", "", "   "), null, null, null);

        assertThat(filtre.isEmpty()).isTrue();
        assertThat(filtre.jpql()).isEmpty();
    }

    @Test
    void bindsNoMotifParameterWhenNoneIsQueryable() {
        new AnalyticsFilter(null, null, null, List.of("INCONNU"), null, null, null).bind(query);

        verify(query, never()).setParameter(eq("fMotifs"), any());
    }

    // ── Liaison des paramètres ──────────────────────────────────────────────

    @Test
    void bindsExactlyTheParametersItsClausesReferenced() {
        withDrivers(DRIVER).bind(query);

        verify(query).setParameter("fDriverIds", List.of(DRIVER));
        verify(query, never()).setParameter(eq("fZoneIds"), any());
        verify(query, never()).setParameter(eq("fStatuses"), any());
    }

    /** En SQL natif les énumérations partent par leur nom, pas par leur ordinal. */
    @Test
    void bindsEnumerationsByNameForTheNativeQuery() {
        new AnalyticsFilter(null, null, List.of(DeliveryStatus.DELIVERED), null,
                null, List.of(OrderSource.ERPNEXT), null).bindNative(query);

        verify(query).setParameter("fStatusNames", List.of("DELIVERED"));
        verify(query).setParameter("fSourceNames", List.of("ERPNEXT"));
    }

    @Test
    void bindsMotifsByNameForTheNativeQuery() {
        new AnalyticsFilter(null, null, null, List.of("REFUSED"), null, null, null).bindNative(query);

        verify(query).setParameter("fMotifs", List.of(FailureCode.REFUSED.name()));
    }
}
