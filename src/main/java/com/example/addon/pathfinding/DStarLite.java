package com.example.addon.pathfinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * D* Lite (Koenig & Likhachev, 2002): incremental replanning search.
 * Searches BACKWARD from goal to start so most of the search tree survives
 * when start moves; updateVertex/computeShortestPath only touch vertices
 * whose cost actually changed, instead of a full replan from scratch.
 *
 * Pure algorithm, no Minecraft dependency -- see NetherGraph for the
 * BlockPos-specific Graph implementation this is meant to run over.
 */
public final class DStarLite<N> {

    /** Undirected grid: neighbors() doubles as both successors and predecessors. */
    public interface Graph<N> {
        List<N> neighbors(N node);
        /** Double.POSITIVE_INFINITY if b is blocked/unreachable from a. */
        double cost(N a, N b);
        /** Admissible estimate (e.g. Euclidean distance) -- never overestimates true cost. */
        double heuristic(N a, N b);
    }

    private static final double INF = Double.POSITIVE_INFINITY;

    public static final class Key implements Comparable<Key> {
        final double k1, k2;
        Key(double k1, double k2) { this.k1 = k1; this.k2 = k2; }
        @Override public int compareTo(Key o) {
            int c = Double.compare(k1, o.k1);
            return c != 0 ? c : Double.compare(k2, o.k2);
        }
        @Override public boolean equals(Object obj) {
            return obj instanceof Key k && k.k1 == k1 && k.k2 == k2;
        }
        @Override public int hashCode() { return Double.hashCode(k1) * 31 + Double.hashCode(k2); }
    }

    private final Graph<N> graph;
    private N start;
    private N goal;
    private double km;
    private N lastStart;

    private final Map<N, Double> g = new HashMap<>();
    private final Map<N, Double> rhs = new HashMap<>();
    private final TreeMap<Key, Set<N>> queueByKey = new TreeMap<>();
    private final Map<N, Key> queuedKey = new HashMap<>();

    public DStarLite(Graph<N> graph) { this.graph = graph; }

    private double gOf(N n)   { return g.getOrDefault(n, INF); }
    private double rhsOf(N n) { return rhs.getOrDefault(n, INF); }

    private Key calculateKey(N s) {
        double m = Math.min(gOf(s), rhsOf(s));
        return new Key(m + graph.heuristic(s, start) + km, m);
    }

    private void queueInsert(N s, Key k) {
        queueByKey.computeIfAbsent(k, key -> new LinkedHashSet<>()).add(s);
        queuedKey.put(s, k);
    }

    private void queueRemove(N s) {
        Key k = queuedKey.remove(s);
        if (k == null) return;
        Set<N> bucket = queueByKey.get(k);
        if (bucket != null) {
            bucket.remove(s);
            if (bucket.isEmpty()) queueByKey.remove(k);
        }
    }

    private boolean queueContains(N s) { return queuedKey.containsKey(s); }

    private Key queueTopKey() {
        return queueByKey.isEmpty() ? new Key(INF, INF) : queueByKey.firstKey();
    }

    private N queuePop() {
        Map.Entry<Key, Set<N>> first = queueByKey.firstEntry();
        Iterator<N> it = first.getValue().iterator();
        N s = it.next();
        it.remove();
        if (first.getValue().isEmpty()) queueByKey.remove(first.getKey());
        queuedKey.remove(s);
        return s;
    }

    private void updateVertex(N u) {
        boolean inQueue = queueContains(u);
        boolean consistent = gOf(u) == rhsOf(u);
        if (!consistent) {
            if (inQueue) queueRemove(u);
            queueInsert(u, calculateKey(u));
        } else if (inQueue) {
            queueRemove(u);
        }
    }

    /** Call once before the first {@link #computeShortestPath()}. */
    public void initialize(N start, N goal) {
        g.clear(); rhs.clear(); queueByKey.clear(); queuedKey.clear();
        this.start = start; this.lastStart = start; this.goal = goal; this.km = 0;
        rhs.put(goal, 0.0);
        queueInsert(goal, calculateKey(goal));
    }

    public void computeShortestPath() {
        while (!queueByKey.isEmpty()
                && (queueTopKey().compareTo(calculateKey(start)) < 0 || rhsOf(start) != gOf(start))) {
            Key kOld = queueTopKey();
            N u = queuePop();
            Key kNew = calculateKey(u);
            if (kOld.compareTo(kNew) < 0) {
                queueInsert(u, kNew);
            } else if (gOf(u) > rhsOf(u)) {
                g.put(u, rhsOf(u));
                for (N s : graph.neighbors(u)) {
                    if (!s.equals(goal)) rhs.put(s, Math.min(rhsOf(s), graph.cost(s, u) + gOf(u)));
                    updateVertex(s);
                }
            } else {
                double gOld = gOf(u);
                g.put(u, INF);
                List<N> affected = new ArrayList<>(graph.neighbors(u));
                affected.add(u);
                for (N s : affected) {
                    if (rhsOf(s) == graph.cost(s, u) + gOld && !s.equals(goal)) {
                        double best = INF;
                        for (N sp : graph.neighbors(s)) best = Math.min(best, graph.cost(s, sp) + gOf(sp));
                        rhs.put(s, best);
                    }
                    updateVertex(s);
                }
            }
        }
    }

    /** Call after the start actually moves, BEFORE the next computeShortestPath(). */
    public void updateStart(N newStart) {
        km += graph.heuristic(lastStart, newStart);
        lastStart = newStart;
        start = newStart;
    }

    /** Call when an edge's cost changed (chunk loaded, obstacle revealed). */
    public void updateEdgeCost(N a, N b) {
        for (N s : List.of(a, b)) {
            if (!s.equals(goal)) {
                double best = INF;
                for (N sp : graph.neighbors(s)) best = Math.min(best, graph.cost(s, sp) + gOf(sp));
                rhs.put(s, best);
            }
            updateVertex(s);
        }
    }

    /** Greedy next step from `from`: the neighbor minimizing cost+g. Null if unreachable. */
    public N nextStep(N from) {
        N best = null;
        double bestCost = INF;
        for (N s : graph.neighbors(from)) {
            double c = graph.cost(from, s) + gOf(s);
            if (c < bestCost) { bestCost = c; best = s; }
        }
        return best;
    }

    public double gCost(N n) { return gOf(n); }
}
