package com.asm.delivery.service.route;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orders a route's stops when some of them must come after others.
 *
 * <p>A depot pickup is not a stop like any other: every delivery it loads has to follow it. A plain
 * travelling-salesman answer knows nothing of that, so the optimiser used to repair it by pushing
 * all pickups to the front. That is feasible and expensive — on a Tunis round with one Sousse
 * pickup it drove 130 km south before delivering anything in Tunis, then back again.
 *
 * <p>So the constraint is given to the sequencer instead of being patched onto its answer. The
 * search is deliberately small: a nearest-neighbour build that only ever considers stops whose
 * predecessors are already placed, then relocation passes that reject any move breaking the order.
 * Routes here hold tens of stops, not thousands, and an exact solver would buy nothing a jury or a
 * dispatcher could see.
 *
 * <p>Every candidate is scored on the same matrix and the cheapest feasible one wins — including
 * the order that came in. The result is therefore never worse than what the caller already had,
 * which is the property that lets this replace the old behaviour without a flag.
 */
public final class PrecedenceAwareSequencer {

    /** Guards against a pathological input; relocation converges long before this. */
    private static final int MAX_PASSES = 20;

    private final double[][] cost;
    private final Map<Integer, Set<Integer>> mustFollow;
    private final int stopCount;

    /**
     * @param cost       square matrix where index 0 is the departure depot and index {@code i + 1}
     *                   is stop {@code i} — the convention OSRM's table endpoint already returns
     * @param mustFollow stop index → the stop indices that must precede it
     */
    public PrecedenceAwareSequencer(double[][] cost, Map<Integer, Set<Integer>> mustFollow, int stopCount) {
        this.cost = cost;
        this.mustFollow = mustFollow;
        this.stopCount = stopCount;
    }

    /**
     * The cheapest feasible order among the candidates, or the first feasible one if none can be
     * scored. {@code candidates} are tried in the order given; ties keep the earlier one, so passing
     * the current order first means an equal-cost result never reshuffles the dispatcher's screen.
     */
    public List<Integer> best(List<List<Integer>> candidates) {
        List<Integer> winner = null;
        double winnerCost = Double.MAX_VALUE;

        for (List<Integer> candidate : candidates) {
            List<Integer> feasible = repair(candidate);
            if (feasible == null) continue;
            List<Integer> improved = relocateUntilStable(feasible);
            double c = cost(improved);
            if (c < winnerCost - 1e-6) {
                winnerCost = c;
                winner = improved;
            }
        }
        return winner;
    }

    /** Total travel of an order, depot included as the starting point. */
    public double cost(List<Integer> order) {
        if (order.isEmpty()) return 0;
        double total = cost[0][order.get(0) + 1];
        for (int i = 1; i < order.size(); i++) {
            total += cost[order.get(i - 1) + 1][order.get(i) + 1];
        }
        return total;
    }

    public boolean isFeasible(List<Integer> order) {
        Set<Integer> placed = new LinkedHashSet<>();
        for (Integer stop : order) {
            for (Integer predecessor : mustFollow.getOrDefault(stop, Set.of())) {
                if (!placed.contains(predecessor)) return false;
            }
            placed.add(stop);
        }
        return true;
    }

    /**
     * Makes an order feasible while disturbing it as little as possible: each stop is taken in the
     * given sequence and appended as soon as its predecessors are there, the blocked ones waiting
     * their turn. Returns null when the order is not a complete permutation, or when a cycle in the
     * constraints makes it unsatisfiable — the caller then keeps its own answer rather than trusting
     * a half-built one.
     */
    List<Integer> repair(List<Integer> order) {
        if (order.size() != stopCount || new LinkedHashSet<>(order).size() != stopCount) return null;

        List<Integer> out = new ArrayList<>(stopCount);
        Set<Integer> placed = new LinkedHashSet<>();
        List<Integer> waiting = new ArrayList<>(order);

        while (!waiting.isEmpty()) {
            boolean progressed = false;
            for (int i = 0; i < waiting.size(); i++) {
                Integer stop = waiting.get(i);
                if (placed.containsAll(mustFollow.getOrDefault(stop, Set.of()))) {
                    out.add(stop);
                    placed.add(stop);
                    waiting.remove(i);
                    progressed = true;
                    break;
                }
            }
            if (!progressed) return null;    // cyclic constraints — unsatisfiable
        }
        return out;
    }

    /**
     * Nearest neighbour from the depot, restricted at every step to the stops whose predecessors are
     * already placed. Choosing the nearest *available* stop rather than the nearest stop outright is
     * the whole difference: it will not cross the country to fetch a load it does not need yet.
     */
    List<Integer> nearestNeighbour() {
        List<Integer> out = new ArrayList<>(stopCount);
        Set<Integer> placed = new LinkedHashSet<>();
        int current = 0;                      // depot

        while (out.size() < stopCount) {
            int next = -1;
            double bestCost = Double.MAX_VALUE;
            for (int candidate = 0; candidate < stopCount; candidate++) {
                if (placed.contains(candidate)) continue;
                if (!placed.containsAll(mustFollow.getOrDefault(candidate, Set.of()))) continue;
                double c = cost[current][candidate + 1];
                if (c < bestCost) {
                    bestCost = c;
                    next = candidate;
                }
            }
            if (next < 0) return null;        // nothing available — cyclic constraints
            out.add(next);
            placed.add(next);
            current = next + 1;
        }
        return out;
    }

    /**
     * Repeatedly moves a single stop to its cheapest feasible position, until nothing improves.
     *
     * <p>Relocation rather than segment reversal: reversing a run flips the order of everything
     * inside it, which breaks precedence far more often than it helps, whereas moving one stop
     * leaves every other pair as it was.
     */
    private List<Integer> relocateUntilStable(List<Integer> order) {
        List<Integer> current = new ArrayList<>(order);
        double currentCost = cost(current);

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean improved = false;
            for (int from = 0; from < current.size() && !improved; from++) {
                for (int to = 0; to < current.size(); to++) {
                    if (to == from) continue;
                    List<Integer> moved = new ArrayList<>(current);
                    moved.add(to, moved.remove(from));
                    if (!isFeasible(moved)) continue;
                    double movedCost = cost(moved);
                    if (movedCost < currentCost - 1e-6) {
                        current = moved;
                        currentCost = movedCost;
                        improved = true;
                        break;
                    }
                }
            }
            if (!improved) break;
        }
        return current;
    }

    /** The candidate set every caller should try: what it already has, plus a fresh build. */
    public List<List<Integer>> candidatesFrom(List<Integer> currentOrder, List<Integer> externalOrder) {
        List<List<Integer>> candidates = new ArrayList<>();
        if (currentOrder != null) candidates.add(currentOrder);
        if (externalOrder != null) candidates.add(externalOrder);
        List<Integer> greedy = nearestNeighbour();
        if (greedy != null) candidates.add(greedy);
        return candidates;
    }
}
