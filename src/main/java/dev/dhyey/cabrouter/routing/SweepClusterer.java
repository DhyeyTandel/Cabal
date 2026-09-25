package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits employees into cabs with the sweep heuristic (Gillett and Miller, 1974).
 *
 * <p>Picture a ray from the office rotating like a clock hand. We sort employees by
 * their polar angle and fill cabs in that order. A cab is closed when it runs out of
 * seats, or when adding the next employee would push someone past the ride-time limit.
 *
 * <p>A single sweep depends heavily on where the ray starts, so we try several start
 * angles in both rotational directions. The winner uses the fewest cabs, then the
 * fewest total kilometres, because vehicles cost more than distance.
 */
public final class SweepClusterer {

    private final TravelModel travel;
    private final StopSequencer sequencer;

    public SweepClusterer(TravelModel travel, StopSequencer sequencer) {
        this.travel = travel;
        this.sequencer = sequencer;
    }

    /** @return clusters, each already sequenced outward from the office */
    public List<List<Stop>> cluster(GeoPoint office, List<Stop> stops, RoutingParams params) {
        if (stops.isEmpty()) {
            return List.of();
        }
        List<Stop> byAngle = new ArrayList<>(stops);
        byAngle.sort(Comparator.comparingDouble((Stop s) -> polarAngle(office, s.location()))
                .thenComparingLong(Stop::employeeId));

        int n = byAngle.size();
        int starts = Math.min(n, params.sweepStarts());
        Solution best = null;
        for (int step : new int[] {1, -1}) {
            for (int s = 0; s < starts; s++) {
                int start = (int) ((long) s * n / starts);
                Solution candidate = sweep(office, byAngle, start, step, params);
                if (best == null || candidate.isBetterThan(best)) {
                    best = candidate;
                }
            }
        }
        return best.clusters();
    }

    private Solution sweep(GeoPoint office, List<Stop> byAngle, int start, int step, RoutingParams params) {
        int n = byAngle.size();
        List<List<Stop>> clusters = new ArrayList<>();
        double totalKm = 0;
        List<Stop> current = new ArrayList<>();
        List<Stop> currentRoute = List.of();

        for (int i = 0; i < n; i++) {
            Stop next = byAngle.get(Math.floorMod(start + step * i, n));
            if (!current.isEmpty()) {
                if (current.size() < params.cabCapacity()) {
                    List<Stop> candidate = new ArrayList<>(current);
                    candidate.add(next);
                    List<Stop> route = sequencer.sequence(office, candidate);
                    double ride = RouteMetrics.maxRideMinutes(travel, office, route, params.dwellMinutes());
                    if (ride <= params.maxRideMinutes()) {
                        current = candidate;
                        currentRoute = route;
                        continue;
                    }
                }
                clusters.add(currentRoute);
                totalKm += RouteMetrics.pathKm(travel, office, currentRoute);
                current = new ArrayList<>();
            }
            // A lone employee always gets a cab, even if the trip alone breaks the limit.
            current.add(next);
            currentRoute = List.of(next);
        }
        clusters.add(currentRoute);
        totalKm += RouteMetrics.pathKm(travel, office, currentRoute);
        return new Solution(clusters, totalKm);
    }

    /** Angle in radians, with longitude scaled so a degree east equals a degree north locally. */
    static double polarAngle(GeoPoint office, GeoPoint p) {
        double dx = (p.lng() - office.lng()) * Math.cos(Math.toRadians(office.lat()));
        double dy = p.lat() - office.lat();
        return Math.atan2(dy, dx);
    }

    private record Solution(List<List<Stop>> clusters, double totalKm) {

        boolean isBetterThan(Solution other) {
            if (clusters.size() != other.clusters.size()) {
                return clusters.size() < other.clusters.size();
            }
            return totalKm < other.totalKm - 1e-9;
        }
    }
}
