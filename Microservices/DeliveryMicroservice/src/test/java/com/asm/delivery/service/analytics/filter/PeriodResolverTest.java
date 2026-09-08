package com.asm.delivery.service.analytics.filter;

import com.asm.delivery.exception.AppException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La résolution d'une fenêtre temporelle pour les statistiques d'exploitation.
 *
 * <p>Ce que ces tests protègent : toutes les vues analytiques lisent leurs bornes ici. Une erreur
 * d'un jour ou une heure d'ouverture décalée fausse silencieusement chaque indicateur affiché, sans
 * qu'aucune exception ne soit levée. La fenêtre est ancrée sur Africa/Tunis, et non sur le fuseau du
 * conteneur, pour que « aujourd'hui » veuille dire la même chose sur un poste et sur le serveur.
 */
class PeriodResolverTest {

    private final PeriodResolver resolver = new PeriodResolver();

    private PeriodRange range(String key) {
        return resolver.resolve(key, null, null, null, null, false);
    }

    // ── Fenêtre explicite ───────────────────────────────────────────────────

    @Test
    void acceptsAnExplicitWindowAndLabelsItCustom() {
        var from = LocalDateTime.of(2026, 3, 1, 8, 0);
        var to = LocalDateTime.of(2026, 3, 3, 18, 0);

        var r = resolver.resolve(null, null, from, to, null, false);

        assertThat(r.label()).isEqualTo("custom");
        assertThat(r.start()).isEqualTo(from);
        assertThat(r.end()).isEqualTo(to);
    }

    @Test
    void rejectsAWindowThatEndsBeforeItStarts() {
        var from = LocalDateTime.of(2026, 3, 3, 0, 0);
        var to = LocalDateTime.of(2026, 3, 1, 0, 0);

        assertThatThrownBy(() -> resolver.resolve(null, null, from, to, null, false))
                .isInstanceOf(AppException.class);
    }

    /** Une seule des deux bornes est une erreur d'appel, pas une fenêtre ouverte. */
    @Test
    void rejectsAHalfSpecifiedCustomWindow() {
        assertThatThrownBy(() -> resolver.resolve(null, null, LocalDateTime.now(), null, null, false))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> resolver.resolve(null, null, null, LocalDateTime.now(), null, false))
                .isInstanceOf(AppException.class);
    }

    // ── Fenêtre relative « last » ───────────────────────────────────────────

    @Test
    void readsARelativeWindowExpressedInHours() {
        var r = resolver.resolve(null, "6h", null, null, null, false);

        assertThat(r.label()).isEqualTo("last:6h");
        assertThat(Duration.between(r.start(), r.end()).toHours()).isEqualTo(6);
    }

    @Test
    void readsARelativeWindowExpressedInDaysWeeksAndMonths() {
        var jours = resolver.resolve(null, "90d", null, null, null, false);
        assertThat(Duration.between(jours.start(), jours.end()).toDays()).isEqualTo(90);

        var semaines = resolver.resolve(null, "2w", null, null, null, false);
        assertThat(semaines.label()).isEqualTo("last:2w");
        assertThat(Duration.between(semaines.start(), semaines.end()).toDays()).isEqualTo(14);

        assertThat(resolver.resolve(null, "3m", null, null, null, false).label()).isEqualTo("last:3m");
    }

    @Test
    void acceptsTheRelativeWindowWhateverItsCase() {
        assertThat(resolver.resolve(null, " 12H ", null, null, null, false).label())
                .isEqualTo("last:12h");
    }

    @Test
    void rejectsARelativeWindowThatIsNotANumberFollowedByAUnit() {
        assertThatThrownBy(() -> resolver.resolve(null, "bientot", null, null, null, false))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> resolver.resolve(null, "6y", null, null, null, false))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsAZeroLengthRelativeWindow() {
        assertThatThrownBy(() -> resolver.resolve(null, "0d", null, null, null, false))
                .isInstanceOf(AppException.class);
    }

    /** La fenêtre explicite gagne sur la relative, qui gagne sur le préréglage. */
    @Test
    void appliesThePriorityOrderBetweenTheThreeWaysOfAsking() {
        var from = LocalDateTime.of(2026, 3, 1, 0, 0);
        var to = LocalDateTime.of(2026, 3, 2, 0, 0);

        assertThat(resolver.resolve("ytd", "6h", from, to, null, false).label()).isEqualTo("custom");
        assertThat(resolver.resolve("ytd", "6h", null, null, null, false).label()).isEqualTo("last:6h");
    }

    // ── Préréglages ─────────────────────────────────────────────────────────

    @Test
    void defaultsToTodayWhenNothingIsAsked() {
        assertThat(range(null).label()).isEqualTo("today");
        assertThat(range("  ").label()).isEqualTo("today");
    }

    @Test
    void startsTodayAtMidnightTunis() {
        var r = range("today");
        assertThat(r.start().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(r.start().toLocalDate()).isEqualTo(resolver.now().toLocalDate());
    }

    /** Hier est une journée close : elle s'arrête à minuit, pas à l'instant présent. */
    @Test
    void closesYesterdayAtMidnightRatherThanAtNow() {
        var r = range("yesterday");
        assertThat(r.start().toLocalDate()).isEqualTo(resolver.now().toLocalDate().minusDays(1));
        assertThat(r.end().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(r.end().toLocalDate()).isEqualTo(resolver.now().toLocalDate());
    }

    @Test
    void startsTheWeekOnMonday() {
        assertThat(range("wtd").start().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
    }

    /** Sept jours veut dire aujourd'hui plus les six précédents, pas sept en plus d'aujourd'hui. */
    @Test
    void countsTheLastSevenDaysInclusiveOfToday() {
        assertThat(range("last7d").start().toLocalDate())
                .isEqualTo(resolver.now().toLocalDate().minusDays(6));
        assertThat(range("last30d").start().toLocalDate())
                .isEqualTo(resolver.now().toLocalDate().minusDays(29));
    }

    @Test
    void startsTheMonthAndTheYearOnTheirFirstDay() {
        assertThat(range("mtd").start().getDayOfMonth()).isEqualTo(1);
        assertThat(range("ytd").start().getDayOfYear()).isEqualTo(1);
    }

    @Test
    void startsTheQuarterOnItsFirstDay() {
        var start = range("qtd").start().toLocalDate();
        assertThat(start.getDayOfMonth()).isEqualTo(1);
        assertThat(start.getMonthValue()).isIn(1, 4, 7, 10);
    }

    /** Les clés héritées restent servies : d'anciens tableaux de bord les envoient encore. */
    @Test
    void stillHonoursTheLegacyAliases() {
        assertThat(range("day").start()).isEqualTo(range("today").start());
        assertThat(range("week").start()).isEqualTo(range("wtd").start());
        assertThat(range("month").start()).isEqualTo(range("mtd").start());
        assertThat(range("year").start()).isEqualTo(range("ytd").start());
    }

    @Test
    void acceptsAPresetWhateverItsCaseOrSurroundingSpaces() {
        assertThat(range("  YTD ").label()).isEqualTo("ytd");
    }

    @Test
    void rejectsAnUnknownPreset() {
        assertThatThrownBy(() -> range("depuis-toujours")).isInstanceOf(AppException.class);
    }

    // ── Granularité ─────────────────────────────────────────────────────────

    @Test
    void derivesTheBucketSizeFromTheSpanWhenNoneIsAsked() {
        var base = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(resolver.resolve(null, null, base, base.plusHours(48), null, false).granularity())
                .isEqualTo("hour");
        assertThat(resolver.resolve(null, null, base, base.plusDays(60), null, false).granularity())
                .isEqualTo("day");
        assertThat(resolver.resolve(null, null, base, base.plusDays(200), null, false).granularity())
                .isEqualTo("week");
    }

    @Test
    void honoursAnExplicitBucketSize() {
        var base = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(resolver.resolve(null, null, base, base.plusDays(200), "week", false).granularity())
                .isEqualTo("week");
    }

    @Test
    void rejectsAnUnknownBucketSize() {
        var base = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThatThrownBy(() ->
                resolver.resolve(null, null, base, base.plusDays(1), "minute", false))
                .isInstanceOf(AppException.class);
    }

    /** Une granularité horaire sur un an produirait des milliers de points : elle est refusée. */
    @Test
    void refusesHourlyBucketsBeyondSevenDays() {
        var base = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThatThrownBy(() ->
                resolver.resolve(null, null, base, base.plusDays(30), "hour", false))
                .isInstanceOf(AppException.class);
    }

    // ── Comparaison période sur période ─────────────────────────────────────

    @Test
    void derivesThePrecedingWindowOfEqualLengthWhenComparisonIsAsked() {
        var from = LocalDateTime.of(2026, 3, 10, 0, 0);
        var to = LocalDateTime.of(2026, 3, 20, 0, 0);

        var r = resolver.resolve(null, null, from, to, null, true);

        assertThat(r.hasComparison()).isTrue();
        assertThat(r.prevEnd()).isEqualTo(from);
        assertThat(r.prevStart()).isEqualTo(LocalDateTime.of(2026, 2, 28, 0, 0));
    }

    @Test
    void derivesNoComparisonWhenItIsNotAsked() {
        var r = resolver.resolve("last7d", null, null, null, null, false);
        assertThat(r.hasComparison()).isFalse();
    }

    /** « Tout » n'a pas de période précédente : il n'y a rien avant le début. */
    @Test
    void derivesNoComparisonForTheAllTimeWindow() {
        assertThat(resolver.resolve("all", null, null, null, null, true).hasComparison()).isFalse();
    }

    // ── Point d'entrée hérité ───────────────────────────────────────────────

    @Test
    void treatsTheLegacyCustomPeriodAsAFullDayRange() {
        var r = resolver.resolveLegacy("custom", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3));

        assertThat(r.start()).isEqualTo(LocalDate.of(2026, 3, 1).atStartOfDay());
        assertThat(r.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 3));
        assertThat(r.end().toLocalTime()).isEqualTo(LocalTime.MAX);
    }

    @Test
    void rejectsALegacyCustomPeriodMissingOneBound() {
        assertThatThrownBy(() -> resolver.resolveLegacy("custom", LocalDate.of(2026, 3, 1), null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void fallsBackOnThePresetPathWhenTheLegacyCallCarriesNoDates() {
        assertThat(resolver.resolveLegacy("mtd", null, null).label()).isEqualTo("mtd");
    }
}
