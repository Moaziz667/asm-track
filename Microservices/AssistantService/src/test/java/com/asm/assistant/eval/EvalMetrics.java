package com.asm.assistant.eval;

import java.util.List;

/**
 * Standard retrieval-quality metrics, computed from a per-rank relevance vector (index 0 = top hit).
 * Binary relevance (a retrieved chunk's document matches an expected source or it doesn't), which is
 * what the golden set encodes. Pure functions — unit-tested without any live embeddings.
 */
public final class EvalMetrics {

    private EvalMetrics() {}

    /** Recall@K for a single-target query: 1.0 if a relevant item appears in the top K, else 0.0. */
    public static double hitAtK(List<Boolean> relevance, int k) {
        int n = Math.min(k, relevance.size());
        for (int i = 0; i < n; i++) if (relevance.get(i)) return 1.0;
        return 0.0;
    }

    /** Reciprocal rank: 1/(rank of first relevant), 0 if none. */
    public static double reciprocalRank(List<Boolean> relevance) {
        for (int i = 0; i < relevance.size(); i++) if (relevance.get(i)) return 1.0 / (i + 1);
        return 0.0;
    }

    /** Discounted cumulative gain over the top K (binary gains). */
    public static double dcgAtK(List<Boolean> relevance, int k) {
        int n = Math.min(k, relevance.size());
        double dcg = 0.0;
        for (int i = 0; i < n; i++) {
            if (relevance.get(i)) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return dcg;
    }

    /** Normalized DCG@K: DCG divided by the ideal DCG (all relevant items ranked first). */
    public static double ndcgAtK(List<Boolean> relevance, int k) {
        double dcg = dcgAtK(relevance, k);
        long relevant = relevance.stream().filter(Boolean::booleanValue).count();
        int ideal = (int) Math.min(relevant, k);
        double idcg = 0.0;
        for (int i = 0; i < ideal; i++) idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /** Aggregate mean of a metric over many queries. */
    public static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
