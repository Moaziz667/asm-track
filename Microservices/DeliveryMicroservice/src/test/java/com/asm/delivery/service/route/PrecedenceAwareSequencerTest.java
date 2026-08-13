package com.asm.delivery.service.route;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrecedenceAwareSequencerTest {

    /**
     * The real round that exposed this, in kilometres from the Tunis depot: two customers in Tunis,
     * one in Sousse, and a Sousse pickup that one of the Tunis orders depends on.
     *
     * <p>index 0 depot Tunis · 1 Zitouna (Tunis) · 2 pickup Sousse · 3 El Manar (Tunis) · 4 Oliviers (Sousse)
     */
    private static final double[][] TUNIS_SOUSSE = matrix(new double[][]{
            {0, 0},        // depot Tunis
            {4, -2},       // stop 0 — Zitouna, Tunis
            {-113, 38},    // stop 1 — PICKUP Sousse
            {-6, -7},      // stop 2 — El Manar, Tunis (needs the Sousse pickup)
            {-108, 37},    // stop 3 — Oliviers, Sousse (needs the Sousse pickup)
    });

    /** El Manar and Oliviers both wait on the Sousse pickup, which is stop index 1. */
    private static final Map<Integer, Set<Integer>> AFTER_SOUSSE = Map.of(2, Set.of(1), 3, Set.of(1));

    private PrecedenceAwareSequencer sequencer() {
        return new PrecedenceAwareSequencer(TUNIS_SOUSSE, AFTER_SOUSSE, 4);
    }

    @Test
    void deliversBothSousseStopsBeforeDrivingBackToTunis() {
        PrecedenceAwareSequencer s = sequencer();

        List<Integer> best = s.best(s.candidatesFrom(List.of(0, 1, 2, 3), null));

        // Zitouna (Tunis) → pickup Sousse → Oliviers (Sousse) → El Manar (Tunis).
        assertThat(best).containsExactly(0, 1, 3, 2);
    }

    /**
     * What the service used to produce: the pickup pushed to the front, then the deliveries in the
     * order they came. It is feasible, and it drives to Sousse and back twice.
     */
    @Test
    void beatsThePickupsFirstRepairItReplaces() {
        PrecedenceAwareSequencer s = sequencer();
        List<Integer> pickupsFirst = List.of(1, 0, 2, 3);

        List<Integer> best = s.best(s.candidatesFrom(List.of(0, 1, 2, 3), null));

        assertThat(s.cost(best)).isLessThan(s.cost(pickupsFirst));
        assertThat(s.isFeasible(pickupsFirst)).isTrue();     // the old answer was never invalid
    }

    @Test
    void neverReturnsAnOrderThatDeliversBeforeItsPickup() {
        PrecedenceAwareSequencer s = sequencer();

        List<Integer> best = s.best(s.candidatesFrom(List.of(2, 3, 1, 0), null));

        assertThat(s.isFeasible(best)).isTrue();
    }

    /** The property that lets this replace the old behaviour outright. */
    @Test
    void isNeverWorseThanTheOrderItWasGiven() {
        PrecedenceAwareSequencer s = sequencer();
        List<Integer> current = List.of(0, 1, 2, 3);

        List<Integer> best = s.best(s.candidatesFrom(current, null));

        assertThat(s.cost(best)).isLessThanOrEqualTo(s.cost(current));
    }

    /** An OSRM trip answer that ignores precedence is repaired, not trusted as-is. */
    @Test
    void repairsAnInfeasibleSuggestionInsteadOfRefusingIt() {
        PrecedenceAwareSequencer s = sequencer();

        List<Integer> best = s.best(s.candidatesFrom(List.of(0, 1, 2, 3), List.of(0, 2, 3, 1)));

        assertThat(s.isFeasible(best)).isTrue();
        assertThat(best).hasSize(4).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    /**
     * No pickup on the route — the single-depot case, which is every Odoo round and most others.
     * With no constraint the sequencer is a plain nearest-neighbour improver, and it must still
     * return a complete, cheaper-or-equal order.
     */
    @Test
    void aRouteWithoutPickupsIsOrderedAsBefore() {
        PrecedenceAwareSequencer s = new PrecedenceAwareSequencer(TUNIS_SOUSSE, Map.of(), 4);
        List<Integer> current = List.of(3, 2, 1, 0);

        List<Integer> best = s.best(s.candidatesFrom(current, null));

        assertThat(best).hasSize(4).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(s.cost(best)).isLessThanOrEqualTo(s.cost(current));
    }

    @Test
    void aSingleStopIsLeftAlone() {
        PrecedenceAwareSequencer s = new PrecedenceAwareSequencer(
                matrix(new double[][]{{0, 0}, {5, 5}}), Map.of(), 1);

        assertThat(s.best(s.candidatesFrom(List.of(0), null))).containsExactly(0);
    }

    /** Constraints that cannot be satisfied must yield nothing, so the caller keeps its own order. */
    @Test
    void refusesToInventAnOrderForCyclicConstraints() {
        PrecedenceAwareSequencer s = new PrecedenceAwareSequencer(
                TUNIS_SOUSSE, Map.of(0, Set.of(1), 1, Set.of(0)), 4);

        assertThat(s.best(s.candidatesFrom(List.of(0, 1, 2, 3), null))).isNull();
    }

    /** Straight-line distances between plane points — enough to rank orders. */
    private static double[][] matrix(double[][] points) {
        int n = points.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double dx = points[i][0] - points[j][0];
                double dy = points[i][1] - points[j][1];
                m[i][j] = Math.sqrt(dx * dx + dy * dy);
            }
        }
        return m;
    }
}
