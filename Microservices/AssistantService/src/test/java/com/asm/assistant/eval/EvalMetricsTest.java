package com.asm.assistant.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the metric math deterministically — no embeddings, no network. */
class EvalMetricsTest {

    @Test
    void hitAtK_findsRelevantWithinK() {
        assertThat(EvalMetrics.hitAtK(List.of(false, true, false), 5)).isEqualTo(1.0);
        assertThat(EvalMetrics.hitAtK(List.of(false, false, true), 2)).isEqualTo(0.0); // relevant is at rank 3
        assertThat(EvalMetrics.hitAtK(List.of(false, false), 5)).isEqualTo(0.0);
    }

    @Test
    void reciprocalRank_isInverseOfFirstRelevantRank() {
        assertThat(EvalMetrics.reciprocalRank(List.of(true, false))).isEqualTo(1.0);
        assertThat(EvalMetrics.reciprocalRank(List.of(false, false, true))).isEqualTo(1.0 / 3);
        assertThat(EvalMetrics.reciprocalRank(List.of(false, false))).isEqualTo(0.0);
    }

    @Test
    void ndcg_isOneWhenRelevantIsFirst_andLessWhenLower() {
        assertThat(EvalMetrics.ndcgAtK(List.of(true, false, false), 10)).isEqualTo(1.0);
        double lower = EvalMetrics.ndcgAtK(List.of(false, true, false), 10);
        assertThat(lower).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(EvalMetrics.ndcgAtK(List.of(false, false, false), 10)).isEqualTo(0.0);
    }

    @Test
    void mean_aggregatesAcrossQueries() {
        assertThat(EvalMetrics.mean(List.of(1.0, 0.0, 1.0, 0.0))).isEqualTo(0.5);
        assertThat(EvalMetrics.mean(List.of())).isEqualTo(0.0);
    }
}
