package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orders the stops inside one cab. Nearest-neighbour builds a first route, then 2-opt
 * and Or-opt improve it in turns until neither can shorten it further.
 *
 * <p>The path is open. It starts at the office and ends at whichever stop comes last.
 * It can optionally be forced to end at a fixed point, which the escort repair uses.
 * Because the far end is open, 2-opt may reverse a suffix of the route, which a
 * closed-tour 2-opt would never do.
 */
public final class StopSequencer {

    private static final double EPS = 1e-9;

    private final TravelModel travel;

    public StopSequencer(TravelModel travel) {
        this.travel = travel;
    }

    public List<Stop> sequence(GeoPoint origin, List<Stop> stops) {
        return sequence(origin, stops, null);
    }

    /** @param end fixed point the path must finish at (not one of {@code stops}), or null */
    public List<Stop> sequence(GeoPoint origin, List<Stop> stops, GeoPoint end) {
        List<Stop> route = nearestNeighbour(origin, stops);
        while (true) {
            route = twoOpt(origin, route, end);
            List<Stop> moved = orOpt(origin, route, end);
            if (moved == null) {
                return route;
            }
            route = moved;
        }
    }

    List<Stop> nearestNeighbour(GeoPoint origin, List<Stop> stops) {
        List<Stop> remaining = new ArrayList<>(stops);
        List<Stop> route = new ArrayList<>(stops.size());
        GeoPoint current = origin;
        while (!remaining.isEmpty()) {
            int best = 0;
            double bestKm = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                double km = travel.distanceKm(current, remaining.get(i).location());
                if (km < bestKm) {
                    bestKm = km;
                    best = i;
                }
            }
            Stop next = remaining.remove(best);
            route.add(next);
            current = next.location();
        }
        return route;
    }

    /**
     * Reversing route[i..j] swaps edges (before, first) + (last, after) for
     * (before, last) + (first, after). Every edge inside the segment keeps its
     * length because distances are symmetric. We keep applying improving
     * reversals until none is left, which gives a 2-opt local optimum.
     */
    List<Stop> twoOpt(GeoPoint origin, List<Stop> initial, GeoPoint end) {
        List<Stop> route = new ArrayList<>(initial);
        int n = route.size();
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    GeoPoint before = i == 0 ? origin : route.get(i - 1).location();
                    GeoPoint first = route.get(i).location();
                    GeoPoint last = route.get(j).location();
                    GeoPoint after = j == n - 1 ? end : route.get(j + 1).location();

                    double delta = travel.distanceKm(before, last) - travel.distanceKm(before, first);
                    if (after != null) {
                        delta += travel.distanceKm(first, after) - travel.distanceKm(last, after);
                    }
                    if (delta < -EPS) {
                        Collections.reverse(route.subList(i, j + 1));
                        improved = true;
                    }
                }
            }
        }
        return route;
    }

    /**
     * Or-opt: lift out a run of 1 to 3 consecutive stops and reinsert it elsewhere,
     * either way round. 2-opt can only reverse segments in place, so it cannot move
     * one stranded stop to the other end of the route. Or-opt can.
     *
     * <p>Cabs hold at most about a dozen people, so each candidate is scored by
     * recomputing the full path length. That is simpler than delta arithmetic and
     * still fast.
     *
     * @return the first improving move applied, or null at a local optimum
     */
    List<Stop> orOpt(GeoPoint origin, List<Stop> route, GeoPoint end) {
        int n = route.size();
        double current = RouteMetrics.pathKm(travel, origin, route, end);
        for (int len = 1; len <= Math.min(3, n - 1); len++) {
            for (int i = 0; i + len <= n; i++) {
                List<Stop> segment = new ArrayList<>(route.subList(i, i + len));
                List<Stop> rest = new ArrayList<>(route);
                rest.subList(i, i + len).clear();
                for (int pos = 0; pos <= rest.size(); pos++) {
                    if (pos == i) {
                        continue;
                    }
                    for (boolean reversed : new boolean[] {false, true}) {
                        List<Stop> seg = new ArrayList<>(segment);
                        if (reversed) {
                            Collections.reverse(seg);
                        }
                        List<Stop> candidate = new ArrayList<>(rest);
                        candidate.addAll(pos, seg);
                        if (RouteMetrics.pathKm(travel, origin, candidate, end) < current - EPS) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }
}
